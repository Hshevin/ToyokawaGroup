#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import sys
from dataclasses import asdict
from itertools import product
from pathlib import Path

import torch

TOOLS_DIR = Path(__file__).resolve().parent
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

from fp8_quant_utils import Fp8QuantConfig, apply_fp8_weight_only, export_fp8_pack, pack_file_size_mb
from quantize_fp8_unet import (
    build_model,
    evaluate,
    evaluate_jit,
    file_size_mb,
    load_checkpoint,
    prune_model,
    threshold_from_spec,
)


def run_one(
    base_state: dict[str, torch.Tensor],
    is_pruned: bool,
    prune_ratios: tuple[float, float, float],
    config: Fp8QuantConfig,
    h: int,
    w: int,
    images_dir: Path,
    masks_dir: Path,
    mean: list[float],
    std: list[float],
    threshold: float,
    output_dir: Path,
    tag: str,
) -> dict:
    work = build_model()
    if is_pruned:
        prune_model(work, h, w, *prune_ratios)
    work.load_state_dict(base_state, strict=False)
    apply_fp8_weight_only(work, config)
    eval_result = evaluate(work, images_dir, masks_dir, w, h, mean, std, threshold)

    pack_path = output_dir / f"{tag}.fp8pkg"
    manifest = export_fp8_pack(work, config, pack_path)

    wrapped = build_model()
    if is_pruned:
        prune_model(wrapped, h, w, *prune_ratios)
    wrapped.load_state_dict(base_state, strict=False)
    apply_fp8_weight_only(wrapped, config)
    wrapped.eval()
    traced = torch.jit.trace(wrapped, torch.randn(1, 3, h, w))
    runtime_pt = output_dir / f"{tag}_runtime.pt"
    traced.save(str(runtime_pt))

    return {
        "tag": tag,
        "config": {
            "fmt": config.fmt,
            "granularity": config.granularity,
            "skip_prefixes": list(config.skip_prefixes),
        },
        "eval": asdict(eval_result),
        "fp8_pack_mb": pack_file_size_mb(pack_path),
        "runtime_pt_mb": file_size_mb(runtime_pt),
        "manifest_blob_kb": manifest["blob_size_bytes"] / 1024,
        "pack_path": str(pack_path),
        "runtime_pt_path": str(runtime_pt),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Tune FP8 quantization configs.")
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--model-spec", type=Path, required=True)
    parser.add_argument("--images-dir", type=Path, required=True)
    parser.add_argument("--masks-dir", type=Path, required=True)
    parser.add_argument("--baseline-torchscript", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--iou-drop-gate-pct", type=float, default=1.5)
    parser.add_argument("--prune", action="store_true")
    parser.add_argument("--ratio-shallow", type=float, default=0.08)
    parser.add_argument("--ratio-mid", type=float, default=0.15)
    parser.add_argument("--ratio-deep", type=float, default=0.25)
    parser.add_argument("--report-json", type=Path, default=Path("docs/fp8_tune_results.json"))
    args = parser.parse_args()

    spec = json.loads(args.model_spec.read_text(encoding="utf-8-sig"))
    h = int(spec["input"]["height"])
    w = int(spec["input"]["width"])
    mean = [float(v) for v in spec["input"]["mean"]]
    std = [float(v) for v in spec["input"]["std"]]
    threshold = threshold_from_spec(spec)

    baseline_eval = evaluate_jit(
        args.baseline_torchscript, args.images_dir, args.masks_dir, w, h, mean, std, threshold,
    )
    baseline_iou = baseline_eval.iou
    baseline_pt_mb = file_size_mb(args.baseline_torchscript)

    base_model = build_model()
    prune_ratios = (args.ratio_shallow, args.ratio_mid, args.ratio_deep)
    if args.prune:
        prune_model(base_model, h, w, *prune_ratios)
    load_checkpoint(base_model, args.checkpoint)
    base_state = {k: v.clone() for k, v in base_model.state_dict().items()}

    skip_sets = [
        ("default", ("encoder._conv_stem", "segmentation_head")),
        ("no_skip", ()),
        ("skip_decoder", ("encoder._conv_stem", "segmentation_head", "decoder")),
    ]

    args.output_dir.mkdir(parents=True, exist_ok=True)
    rows: list[dict] = []

    for fmt, gran, (skip_tag, skip_prefixes) in product(
        ["e4m3fn", "e5m2"], ["per_tensor", "per_channel"], skip_sets,
    ):
        config = Fp8QuantConfig(fmt=fmt, granularity=gran, skip_prefixes=skip_prefixes)
        tag = f"{fmt}_{gran}_{skip_tag}"
        print(f"\n=== try {tag} ===")
        row = run_one(
            base_state, args.prune, prune_ratios, config, h, w,
            args.images_dir, args.masks_dir, mean, std, threshold,
            args.output_dir, tag,
        )
        row["iou_drop_pct"] = (baseline_iou - row["eval"]["iou"]) * 100.0
        row["pass_iou_gate"] = row["iou_drop_pct"] <= args.iou_drop_gate_pct
        row["runtime_pt_vs_baseline"] = row["runtime_pt_mb"] / baseline_pt_mb if baseline_pt_mb else 1.0
        row["fp8_pack_vs_baseline"] = row["fp8_pack_mb"] / baseline_pt_mb if baseline_pt_mb else 1.0
        rows.append(row)
        print(
            f"  IoU={row['eval']['iou']:.4f} drop={row['iou_drop_pct']:.2f}% "
            f"pack={row['fp8_pack_mb']:.2f}MB runtime_pt={row['runtime_pt_mb']:.2f}MB "
            f"pass={row['pass_iou_gate']}"
        )

    passing = [r for r in rows if r["pass_iou_gate"]]
    passing.sort(key=lambda r: (r["fp8_pack_mb"], r["iou_drop_pct"]))
    best = passing[0] if passing else min(rows, key=lambda r: r["iou_drop_pct"])

    canonical_pack = args.output_dir / "best.fp8pkg"
    canonical_runtime = args.output_dir / "best_runtime.pt"
    canonical_pack.write_bytes(Path(best["pack_path"]).read_bytes())
    canonical_runtime.write_bytes(Path(best["runtime_pt_path"]).read_bytes())

    report = {
        "baseline_iou": baseline_iou,
        "baseline_pt_mb": baseline_pt_mb,
        "iou_drop_gate_pct": args.iou_drop_gate_pct,
        "eval_threshold": threshold,
        "candidates": rows,
        "best": best,
        "canonical_pack": str(canonical_pack),
        "canonical_runtime_pt": str(canonical_runtime),
    }
    args.report_json.parent.mkdir(parents=True, exist_ok=True)
    args.report_json.write_text(json.dumps(report, indent=2), encoding="utf-8")

    print("\n=== BEST ===")
    print(f"tag={best['tag']} IoU drop={best['iou_drop_pct']:.2f}%")
    print(f"fp8_pack={best['fp8_pack_mb']:.2f}MB ({best['fp8_pack_vs_baseline']*100:.1f}% of baseline .pt)")
    print(f"runtime_pt={best['runtime_pt_mb']:.2f}MB ({best['runtime_pt_vs_baseline']*100:.1f}% of baseline .pt)")
    print(f"report: {args.report_json}")


if __name__ == "__main__":
    main()

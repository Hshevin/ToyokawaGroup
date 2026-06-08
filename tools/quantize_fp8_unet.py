#!/usr/bin/env python3
"""FP8 weight-only PTQ for SMP UNet(EfficientNet-B0)."""

from __future__ import annotations

import argparse
import json
import sys
import time
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch_pruning as tp
from PIL import Image
from segmentation_models_pytorch import Unet

TOOLS_DIR = Path(__file__).resolve().parent
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

from fp8_quant_utils import (
    Fp8QuantConfig,
    apply_fp8_weight_only,
    export_fp8_pack,
    pack_file_size_mb,
    replace_with_fp8_wrappers,
)


@dataclass
class EvalResult:
    iou: float
    f1: float
    precision: float
    recall: float


@dataclass
class BenchResult:
    latency_avg_ms: float
    latency_p90_ms: float
    file_size_mb: float


@dataclass
class ExperimentRow:
    label: str
    eval: EvalResult
    bench: BenchResult
    theoretical_fp8_size_mb: float


def build_model() -> nn.Module:
    return Unet(
        encoder_name="efficientnet-b0",
        encoder_weights=None,
        in_channels=3,
        classes=1,
        activation=None,
    )


def load_checkpoint(model: nn.Module, ckpt: Path) -> None:
    state = torch.load(ckpt, map_location="cpu", weights_only=False)
    if isinstance(state, dict) and "state_dict" in state and isinstance(state["state_dict"], dict):
        state = state["state_dict"]
    missing, unexpected = model.load_state_dict(state, strict=False)
    if missing:
        print(f"[warn] missing keys: {len(missing)}")
    if unexpected:
        print(f"[warn] unexpected keys: {len(unexpected)}")


def module_ratio(name: str, shallow: float, mid: float, deep: float) -> float:
    if name.startswith("encoder._blocks."):
        try:
            idx = int(name.split(".")[2])
        except Exception:
            return mid
        if idx <= 4:
            return shallow
        if idx <= 10:
            return mid
        return deep
    if name.startswith("decoder."):
        return deep
    return shallow


def prune_model(model: nn.Module, h: int, w: int, shallow: float, mid: float, deep: float) -> None:
    ratios: dict[nn.Module, float] = {}
    ignored: list[nn.Module] = []
    for name, module in model.named_modules():
        if not isinstance(module, nn.Conv2d):
            continue
        if name.startswith("encoder._conv_stem") or name.startswith("encoder._conv_head"):
            ignored.append(module)
            continue
        if name.startswith("segmentation_head"):
            ignored.append(module)
            continue
        ratios[module] = module_ratio(name, shallow, mid, deep)
    pruner = tp.pruner.MagnitudePruner(
        model,
        example_inputs=torch.randn(1, 3, h, w),
        importance=tp.importance.MagnitudeImportance(p=1),
        pruning_ratio=0.0,
        pruning_ratio_dict=ratios,
        ignored_layers=ignored,
        iterative_steps=1,
        round_to=8,
    )
    pruner.step()


def fp8_dtype(name: str) -> torch.dtype:
    from fp8_quant_utils import fp8_dtype as _fp8_dtype
    return _fp8_dtype(name)


def quantize_weight_tensor(weight: torch.Tensor, dtype: torch.dtype) -> torch.Tensor:
    from fp8_quant_utils import dequantize_fp8, quantize_tensor_to_fp8
    codes, scales = quantize_tensor_to_fp8(weight, dtype, "per_tensor")
    return dequantize_fp8(codes, scales, dtype, weight)


def apply_fp8_weight_only_legacy(model: nn.Module, fmt: str) -> tuple[int, int]:
    return apply_fp8_weight_only(model, Fp8QuantConfig(fmt=fmt))


def count_parameters(model: nn.Module) -> int:
    return sum(p.numel() for p in model.parameters())


def count_quantizable_weight_params(model: nn.Module) -> int:
    total = 0
    for module in model.modules():
        if isinstance(module, (nn.Conv2d, nn.Linear, nn.ConvTranspose2d)) and module.weight is not None:
            total += module.weight.numel()
    return total


def weight_storage_estimates(model: nn.Module) -> dict[str, float | int]:
    quantizable = count_quantizable_weight_params(model)
    total = count_parameters(model)
    return {
        "quantizable_weight_params": quantizable,
        "total_params": total,
        "fp32_weight_size_mb": quantizable * 4 / (1024 * 1024),
        "theoretical_fp8_weight_size_mb": quantizable / (1024 * 1024),
    }


def threshold_from_spec(spec: dict) -> float:
    output = spec.get("output") or {}
    threshold = output.get("threshold")
    if threshold is None:
        print("[warn] model_spec missing output.threshold; falling back to 0.5")
        return 0.5
    return float(threshold)


def file_size_mb(path: Path) -> float:
    return path.stat().st_size / (1024 * 1024)


def benchmark_module(module: torch.nn.Module, height: int, width: int, warmup: int, iterations: int) -> BenchResult:
    module.eval()
    x = torch.randn(1, 3, height, width)
    latencies: list[float] = []
    with torch.no_grad():
        for _ in range(warmup):
            module(x)
        for _ in range(iterations):
            t0 = time.perf_counter()
            module(x)
            latencies.append((time.perf_counter() - t0) * 1000.0)
    latencies.sort()
    avg = sum(latencies) / len(latencies)
    p90 = latencies[int(len(latencies) * 0.9) - 1]
    return BenchResult(latency_avg_ms=avg, latency_p90_ms=p90, file_size_mb=0.0)


def list_image_paths(folder: Path) -> list[Path]:
    exts = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
    return sorted([p for p in folder.iterdir() if p.is_file() and p.suffix.lower() in exts])


def load_rgb(path: Path, size: tuple[int, int], mean: list[float], std: list[float]) -> torch.Tensor:
    img = Image.open(path).convert("RGB").resize(size, Image.BILINEAR)
    arr = np.asarray(img, dtype=np.float32) / 255.0
    chw = np.transpose(arr, (2, 0, 1))
    mean_arr = np.asarray(mean, dtype=np.float32)[:, None, None]
    std_arr = np.asarray(std, dtype=np.float32)[:, None, None]
    return torch.from_numpy((chw - mean_arr) / std_arr)


def load_mask(path: Path, size: tuple[int, int]) -> torch.Tensor:
    mask = Image.open(path).convert("L").resize(size, Image.NEAREST)
    arr = np.asarray(mask, dtype=np.uint8)
    return torch.from_numpy((arr > 0).astype(np.float32)).unsqueeze(0)


def metrics_from_logits(
    logits: torch.Tensor,
    target: torch.Tensor,
    threshold: float,
) -> tuple[float, float, float, float]:
    pred = (torch.sigmoid(logits) > threshold).float()
    target = target.float()
    inter = (pred * target).sum()
    union = pred.sum() + target.sum() - inter
    iou = float((inter / union).item()) if union > 0 else 1.0
    tp = inter
    fp = pred.sum() - tp
    fn = target.sum() - tp
    precision = float((tp / (tp + fp)).item()) if (tp + fp) > 0 else 0.0
    recall = float((tp / (tp + fn)).item()) if (tp + fn) > 0 else 0.0
    f1 = (2 * precision * recall / (precision + recall)) if (precision + recall) > 0 else 0.0
    return iou, f1, precision, recall


def evaluate(
    model: nn.Module,
    images_dir: Path,
    masks_dir: Path,
    width: int,
    height: int,
    mean: list[float],
    std: list[float],
    threshold: float,
) -> EvalResult:
    model.eval()
    ious: list[float] = []
    f1s: list[float] = []
    ps: list[float] = []
    rs: list[float] = []
    size = (width, height)
    with torch.no_grad():
        for img_path in list_image_paths(images_dir):
            mask_path = masks_dir / f"{img_path.stem}.png"
            if not mask_path.exists():
                continue
            x = load_rgb(img_path, size, mean, std).unsqueeze(0)
            y = load_mask(mask_path, size).unsqueeze(0)
            logits = model(x)
            if isinstance(logits, (list, tuple)):
                logits = logits[0]
            iou, f1, p, r = metrics_from_logits(logits, y, threshold)
            ious.append(iou)
            f1s.append(f1)
            ps.append(p)
            rs.append(r)
    if not ious:
        raise ValueError(f"No paired masks found under {masks_dir}")
    return EvalResult(
        iou=float(np.mean(ious)),
        f1=float(np.mean(f1s)),
        precision=float(np.mean(ps)),
        recall=float(np.mean(rs)),
    )


def evaluate_jit(
    path: Path,
    images_dir: Path,
    masks_dir: Path,
    width: int,
    height: int,
    mean: list[float],
    std: list[float],
    threshold: float,
) -> EvalResult:
    module = torch.jit.load(str(path), map_location="cpu")
    module.eval()
    ious: list[float] = []
    f1s: list[float] = []
    ps: list[float] = []
    rs: list[float] = []
    size = (width, height)
    with torch.no_grad():
        for img_path in list_image_paths(images_dir):
            mask_path = masks_dir / f"{img_path.stem}.png"
            if not mask_path.exists():
                continue
            x = load_rgb(img_path, size, mean, std).unsqueeze(0)
            y = load_mask(mask_path, size).unsqueeze(0)
            logits = module(x)
            if isinstance(logits, (list, tuple)):
                logits = logits[0]
            iou, f1, p, r = metrics_from_logits(logits, y, threshold)
            ious.append(iou)
            f1s.append(f1)
            ps.append(p)
            rs.append(r)
    return EvalResult(
        iou=float(np.mean(ious)),
        f1=float(np.mean(f1s)),
        precision=float(np.mean(ps)),
        recall=float(np.mean(rs)),
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="FP8 weight-only PTQ for UNet(EfficientNet-B0).")
    parser.add_argument("--checkpoint", type=Path, required=True, help="Source .pth checkpoint (eager weights)")
    parser.add_argument("--model-spec", type=Path, required=True)
    parser.add_argument("--images-dir", type=Path, required=True)
    parser.add_argument("--masks-dir", type=Path, required=True)
    parser.add_argument("--baseline-torchscript", type=Path, required=True)
    parser.add_argument("--output-torchscript", type=Path, required=True)
    parser.add_argument("--fp8-format", choices=["e4m3fn", "e5m2"], default="e4m3fn")
    parser.add_argument("--granularity", choices=["per_tensor", "per_channel"], default="per_channel")
    parser.add_argument(
        "--skip-prefix",
        action="append",
        default=None,
        help="Module name prefixes kept in FP32 (default: encoder._conv_stem, segmentation_head)",
    )
    parser.add_argument(
        "--output-fp8-pack",
        type=Path,
        default=None,
        help="Compact FP8 weight pack for APK (real size reduction)",
    )
    parser.add_argument(
        "--use-fp8-wrappers",
        action="store_true",
        help="Experimental: export TorchScript with FP8 uint8 buffers (may fail trace on UNet)",
    )
    parser.add_argument(
        "--prune",
        action="store_true",
        help="Apply structured prune before loading checkpoint (required for pruned .pth)",
    )
    parser.add_argument("--ratio-shallow", type=float, default=0.08)
    parser.add_argument("--ratio-mid", type=float, default=0.15)
    parser.add_argument("--ratio-deep", type=float, default=0.25)
    parser.add_argument("--warmup", type=int, default=10)
    parser.add_argument("--iterations", type=int, default=40)
    parser.add_argument("--report-json", type=Path, default=Path("docs/fp8_quant_experiment.json"))
    parser.add_argument(
        "--reference-metrics",
        type=Path,
        default=None,
        help="Algorithm metrics.json for cross-check (default: metrics.json beside --checkpoint)",
    )
    args = parser.parse_args()

    spec = json.loads(args.model_spec.read_text(encoding="utf-8-sig"))
    h = int(spec["input"]["height"])
    w = int(spec["input"]["width"])
    mean = [float(v) for v in spec["input"]["mean"]]
    std = [float(v) for v in spec["input"]["std"]]
    threshold = threshold_from_spec(spec)
    skip_prefixes = tuple(args.skip_prefix or ("encoder._conv_stem", "segmentation_head"))
    fp8_config = Fp8QuantConfig(
        fmt=args.fp8_format,
        granularity=args.granularity,
        skip_prefixes=skip_prefixes,
    )

    print("=== FP8 weight-only PTQ experiment ===")
    print(
        f"format={args.fp8_format} granularity={args.granularity} "
        f"input={w}x{h} threshold={threshold} skip={skip_prefixes}"
    )

    baseline_eval = evaluate_jit(
        args.baseline_torchscript, args.images_dir, args.masks_dir, w, h, mean, std, threshold,
    )
    baseline_bench = benchmark_module(torch.jit.load(str(args.baseline_torchscript)), h, w, args.warmup, args.iterations)
    baseline_bench.file_size_mb = file_size_mb(args.baseline_torchscript)

    model = build_model()
    if args.prune:
        print(
            f"structured prune ratios: shallow={args.ratio_shallow} "
            f"mid={args.ratio_mid} deep={args.ratio_deep}"
        )
        prune_model(model, h, w, args.ratio_shallow, args.ratio_mid, args.ratio_deep)
    load_checkpoint(model, args.checkpoint)
    source_eval = evaluate(model, args.images_dir, args.masks_dir, w, h, mean, std, threshold)

    q_layers, skip_layers = apply_fp8_weight_only(model, fp8_config)
    fp8_eval = evaluate(model, args.images_dir, args.masks_dir, w, h, mean, std, threshold)

    pack_path = args.output_fp8_pack
    if pack_path is None:
        pack_path = args.output_torchscript.with_suffix(".fp8pkg")
    pack_manifest = export_fp8_pack(model, fp8_config, pack_path)
    pack_mb = pack_file_size_mb(pack_path)

    if args.use_fp8_wrappers:
        export_model = build_model()
        if args.prune:
            prune_model(export_model, h, w, args.ratio_shallow, args.ratio_mid, args.ratio_deep)
        load_checkpoint(export_model, args.checkpoint)
        replace_with_fp8_wrappers(export_model, fp8_config)
        trace_source = export_model.to("cpu").eval()
    else:
        trace_source = model.to("cpu").eval()
    traced = torch.jit.trace(trace_source, torch.randn(1, 3, h, w))
    args.output_torchscript.parent.mkdir(parents=True, exist_ok=True)
    traced.save(str(args.output_torchscript))

    fp8_bench = benchmark_module(traced, h, w, args.warmup, args.iterations)
    fp8_bench.file_size_mb = file_size_mb(args.output_torchscript)
    storage = weight_storage_estimates(model)

    def print_row(label: str, ev: EvalResult, bench: BenchResult, show_storage: bool = False) -> None:
        print(f"\n[{label}]")
        print(f"  IoU={ev.iou:.4f} F1={ev.f1:.4f} precision={ev.precision:.4f} recall={ev.recall:.4f}")
        print(f"  latency avg={bench.latency_avg_ms:.2f}ms p90={bench.latency_p90_ms:.2f}ms")
        print(f"  exported_torchscript_size={bench.file_size_mb:.2f}MB")
        if show_storage:
            print(f"  fp8_pack_size={pack_mb:.2f}MB ({pack_mb / baseline_bench.file_size_mb * 100:.1f}% of baseline .pt)")
            print(
                f"  weight_storage_estimate: fp32_weights={storage['fp32_weight_size_mb']:.2f}MB "
                f"theoretical_fp8_weights={storage['theoretical_fp8_weight_size_mb']:.2f}MB"
            )
            print(f"  fp8_pack={pack_path}")

    print_row("baseline_torchscript", baseline_eval, baseline_bench)
    print_row("source_checkpoint_fp32", source_eval, BenchResult(0, 0, 0))
    print_row("fp8_weight_only_export", fp8_eval, fp8_bench, show_storage=True)
    print(f"\nquantized_layers={q_layers} skipped={skip_layers}")
    print(f"saved: {args.output_torchscript}")

    iou_drop_pct = (baseline_eval.iou - fp8_eval.iou) * 100.0
    size_ratio = fp8_bench.file_size_mb / baseline_bench.file_size_mb if baseline_bench.file_size_mb > 0 else 1.0
    latency_ratio = fp8_bench.latency_avg_ms / baseline_bench.latency_avg_ms if baseline_bench.latency_avg_ms > 0 else 1.0

    reference_metrics_path = args.reference_metrics or (args.checkpoint.parent / "metrics.json")
    reference_metrics = None
    if reference_metrics_path.exists():
        reference_metrics = json.loads(reference_metrics_path.read_text(encoding="utf-8-sig"))
        print(f"reference metrics: {reference_metrics_path} (algorithm test-set IoU={reference_metrics.get('iou')})")
    else:
        print(f"[info] no reference metrics at {reference_metrics_path}")

    report = {
        "fp8_format": args.fp8_format,
        "granularity": args.granularity,
        "skip_prefixes": list(skip_prefixes),
        "use_fp8_wrappers": args.use_fp8_wrappers,
        "eval_threshold": threshold,
        "quantized_layers": q_layers,
        "skipped_layers": skip_layers,
        "baseline": {"eval": asdict(baseline_eval), "bench": asdict(baseline_bench)},
        "source_fp32": {"eval": asdict(source_eval)},
        "fp8_export": {
            "eval": asdict(fp8_eval),
            "bench": asdict(fp8_bench),
            "weight_storage": storage,
            "fp8_pack_mb": pack_mb,
            "fp8_pack_path": str(pack_path),
            "fp8_pack_blob_bytes": pack_manifest["blob_size_bytes"],
        },
        "vs_baseline": {
            "iou_drop_pct": iou_drop_pct,
            "exported_torchscript_size_ratio": size_ratio,
            "fp8_pack_size_ratio": pack_mb / baseline_bench.file_size_mb if baseline_bench.file_size_mb else 1.0,
            "latency_ratio": latency_ratio,
        },
        "reference_metrics": reference_metrics,
        "notes": [
            "IoU/F1 use model_spec output.threshold (same as App SegmentationPostProcessor).",
            "reference_metrics is algorithm hold-out test set; local eval uses skyedge_vm_test_images.",
            "fp8_pack_mb is the deployable compressed weight artifact (~1 byte/weight + scales).",
            "exported_torchscript_size_mb is runtime graph; use --use-fp8-wrappers for smaller .pt.",
        ],
    }
    args.report_json.parent.mkdir(parents=True, exist_ok=True)
    args.report_json.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(f"report: {args.report_json}")
    print(f"vs baseline: iou_drop={iou_drop_pct:.2f}% size_ratio={size_ratio:.3f} latency_ratio={latency_ratio:.3f}")


if __name__ == "__main__":
    main()

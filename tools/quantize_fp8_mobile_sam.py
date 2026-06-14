#!/usr/bin/env python3
"""Apply FP8 weight-only PTQ to MobileSAM, then export traced encoder/decoder bundles."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import torch

TOOLS_DIR = Path(__file__).resolve().parent
if str(TOOLS_DIR) not in sys.path:
    sys.path.insert(0, str(TOOLS_DIR))

from export_mobile_sam_torchscript import (
    MobileSamDecoder,
    MobileSamEncoder,
    build_prompter,
    trace_modules,
    validate_against_visual_prompter,
)
from fp8_quant_utils import Fp8QuantConfig, apply_fp8_weight_only, export_fp8_pack, pack_file_size_mb

ROOT = TOOLS_DIR.parent
DEFAULT_CKPT = ROOT / "skyedge_mobilesam_delivery/models/mobile_sam_interactive_v1/mobile_sam.pt"
DEFAULT_OUT = TOOLS_DIR / "out/mobile_sam_optimized"


def export_bundle(
    prompter,
    out_dir: Path,
    prefix: str,
    traced_module: torch.jit.ScriptModule,
    fp8_config: Fp8QuantConfig,
) -> dict:
    runtime_path = out_dir / f"{prefix}_fp8_runtime.pt"
    fp8_path = out_dir / f"{prefix}_fp8.fp8pkg"
    traced_module.save(str(runtime_path))
    manifest = export_fp8_pack(prompter.model, fp8_config, fp8_path)
    return {
        "runtime_asset": runtime_path.name,
        "fp8_asset": fp8_path.name,
        "runtime_mb": round(runtime_path.stat().st_size / (1024 * 1024), 2),
        "fp8_mb": round(pack_file_size_mb(fp8_path), 2),
        "manifest_layers": manifest["layer_count"],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="FP8 quantize MobileSAM and export traced bundles.")
    parser.add_argument("--checkpoint", type=Path, default=DEFAULT_CKPT)
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--validate-demo", action="store_true")
    args = parser.parse_args()

    if not args.checkpoint.exists():
        raise SystemExit(f"Checkpoint not found: {args.checkpoint}")

    device = torch.device("cpu")
    prompter = build_prompter(args.checkpoint, device)
    fp8_config = Fp8QuantConfig(
        fmt="e4m3fn",
        granularity="per_tensor",
        skip_prefixes=("image_encoder.patch_embed", "prompt_encoder.pe_layer"),
    )
    quantized, skipped = apply_fp8_weight_only(prompter.model, fp8_config)

    encoder_size = int(prompter.model.image_encoder.img_size)
    traced_encoder, traced_decoder = trace_modules(prompter, encoder_size)
    args.out_dir.mkdir(parents=True, exist_ok=True)

    report = {
        "quantization": {
            "method": "fp8_e4m3fn_weight_only",
            "granularity": fp8_config.granularity,
            "quantized_layers": quantized,
            "skipped_layers": skipped,
        },
        "encoder": export_bundle(prompter, args.out_dir, "mobile_sam_encoder", traced_encoder, fp8_config),
        "decoder": export_bundle(prompter, args.out_dir, "mobile_sam_decoder", traced_decoder, fp8_config),
    }

    if args.validate_demo:
        demo_image = ROOT / "skyedge_mobilesam_delivery/demo/test_images/building/building_demo_image.png"
        if demo_image.exists():
            report["validation"] = validate_against_visual_prompter(
                prompter,
                traced_encoder,
                traced_decoder,
                demo_image,
                (99.0, 14.0),
            )

    report_path = args.out_dir / "fp8_report.json"
    report_path.write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()

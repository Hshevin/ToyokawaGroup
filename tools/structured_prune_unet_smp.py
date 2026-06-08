#!/usr/bin/env python3
"""Structured channel pruning for SMP UNet checkpoints."""

from __future__ import annotations

import argparse
import json
import time
from pathlib import Path

import torch
import torch.nn as nn
import torch_pruning as tp
from segmentation_models_pytorch import Unet


def file_size_mb(path: Path) -> float:
    return path.stat().st_size / (1024 * 1024)


def benchmark(model: torch.nn.Module, height: int, width: int, warmup: int, iterations: int) -> tuple[float, float]:
    model.eval()
    x = torch.randn(1, 3, height, width)
    latencies: list[float] = []
    with torch.no_grad():
        for _ in range(warmup):
            model(x)
        for _ in range(iterations):
            t0 = time.perf_counter()
            model(x)
            latencies.append((time.perf_counter() - t0) * 1000.0)
    latencies.sort()
    avg = sum(latencies) / len(latencies)
    p90 = latencies[int(len(latencies) * 0.9) - 1]
    return avg, p90


def build_model() -> nn.Module:
    return Unet(
        encoder_name="efficientnet-b0",
        encoder_weights=None,
        in_channels=3,
        classes=1,
        activation=None,
    )


def load_checkpoint(model: nn.Module, ckpt: Path) -> None:
    state = torch.load(ckpt, map_location="cpu")
    if isinstance(state, dict) and "state_dict" in state and isinstance(state["state_dict"], dict):
        state = state["state_dict"]
    missing, unexpected = model.load_state_dict(state, strict=False)
    if missing:
        print(f"[warn] missing keys: {len(missing)}")
    if unexpected:
        print(f"[warn] unexpected keys: {len(unexpected)}")


def group_ratio_by_module(name: str, shallow: float, mid: float, deep: float) -> float:
    if name.startswith("encoder._blocks."):
        try:
            block_idx = int(name.split(".")[2])
        except Exception:
            return mid
        if block_idx <= 4:
            return shallow
        if block_idx <= 10:
            return mid
        return deep
    if name.startswith("decoder.") or name.startswith("segmentation_head."):
        return deep
    return shallow


def build_pruning_ratio_dict(model: nn.Module, shallow: float, mid: float, deep: float) -> tuple[dict[nn.Module, float], list[nn.Module]]:
    ratio_dict: dict[nn.Module, float] = {}
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

        ratio = group_ratio_by_module(name, shallow, mid, deep)
        ratio_dict[module] = ratio

    return ratio_dict, ignored


def export_torchscript(model: nn.Module, output: Path, height: int, width: int) -> None:
    model.eval()
    example = torch.randn(1, 3, height, width)
    with torch.no_grad():
        traced = torch.jit.trace(model, example)
        output.parent.mkdir(parents=True, exist_ok=True)
        traced.save(str(output))


def main() -> None:
    parser = argparse.ArgumentParser(description="Structured pruning for SMP UNet(EfficientNet-B0) checkpoints.")
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--spec", type=Path, required=True, help="model_spec.json for input size")
    parser.add_argument("--out", type=Path, required=True, help="output torchscript .pt")
    parser.add_argument("--ratio-shallow", type=float, default=0.10)
    parser.add_argument("--ratio-mid", type=float, default=0.20)
    parser.add_argument("--ratio-deep", type=float, default=0.30)
    parser.add_argument("--warmup", type=int, default=6)
    parser.add_argument("--iterations", type=int, default=20)
    args = parser.parse_args()

    spec = json.loads(args.spec.read_text(encoding="utf-8-sig"))
    h = int(spec["input"]["height"])
    w = int(spec["input"]["width"])

    model = build_model()
    load_checkpoint(model, args.checkpoint)
    base_avg, base_p90 = benchmark(model, h, w, args.warmup, args.iterations)

    ratio_dict, ignored = build_pruning_ratio_dict(
        model,
        shallow=args.ratio_shallow,
        mid=args.ratio_mid,
        deep=args.ratio_deep,
    )

    example_inputs = torch.randn(1, 3, h, w)
    pruner = tp.pruner.MagnitudePruner(
        model,
        example_inputs=example_inputs,
        importance=tp.importance.MagnitudeImportance(p=1),
        pruning_ratio=0.0,
        pruning_ratio_dict=ratio_dict,
        ignored_layers=ignored,
        iterative_steps=1,
        round_to=8,
    )
    pruner.step()

    pruned_avg, pruned_p90 = benchmark(model, h, w, args.warmup, args.iterations)
    export_torchscript(model, args.out, h, w)

    base_size = file_size_mb(args.checkpoint)
    out_size = file_size_mb(args.out)

    print("\n=== Pruning Feasibility Report ===")
    print(f"checkpoint: {args.checkpoint}")
    print(f"output:     {args.out}")
    print(f"input:      1x3x{h}x{w}")
    print(f"ratios:     shallow={args.ratio_shallow:.2f}, mid={args.ratio_mid:.2f}, deep={args.ratio_deep:.2f}")
    print(f"size:       {base_size:.2f}MB -> {out_size:.2f}MB ({out_size/base_size:.3f}x)")
    print(f"latency:    avg {base_avg:.2f}ms -> {pruned_avg:.2f}ms ({pruned_avg/base_avg:.3f}x)")
    print(f"latency p90:{base_p90:.2f}ms -> {pruned_p90:.2f}ms ({pruned_p90/base_p90:.3f}x)")
    print("NOTE: no fine-tuning done. Validate IoU/F1 before any replacement.")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Benchmark TorchScript segmentation models delivered for SkyEdge.

Usage:
  py -3 tools/benchmark_torchscript_models.py \
    --model-spec app/src/main/assets/models/building_unet_efficientnetb0_v1/model_spec.json \
    --model-spec app/src/main/assets/models/road_unet_efficientnetb0_v1/model_spec.json \
    --iterations 40 --warmup 10 \
    --output docs/benchmark_baseline.json
"""

from __future__ import annotations

import argparse
import json
import time
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
import torch


@dataclass
class BenchRow:
    model_id: str
    asset_file: str
    height: int
    width: int
    latency_avg_ms: float
    latency_p50_ms: float
    latency_p90_ms: float
    file_size_mb: float


def file_size_mb(path: Path) -> float:
    return path.stat().st_size / (1024 * 1024)


def benchmark_module(module: torch.jit.ScriptModule, height: int, width: int, warmup: int, iterations: int) -> tuple[float, float, float]:
    x = torch.randn(1, 3, height, width)
    latencies: list[float] = []
    module.eval()
    with torch.no_grad():
        for _ in range(warmup):
            module(x)
        for _ in range(iterations):
            t0 = time.perf_counter()
            module(x)
            latencies.append((time.perf_counter() - t0) * 1000.0)

    arr = np.asarray(latencies, dtype=np.float64)
    avg = float(arr.mean())
    p50 = float(np.percentile(arr, 50))
    p90 = float(np.percentile(arr, 90))
    return avg, p50, p90


def run_one(spec_path: Path, warmup: int, iterations: int) -> BenchRow:
    spec = json.loads(spec_path.read_text(encoding="utf-8-sig"))
    asset_rel = spec["asset_file"]
    h = int(spec["input"]["height"])
    w = int(spec["input"]["width"])

    # Resolve "models/xxx.pt" relative to app/src/main/assets/
    assets_root = spec_path.parent.parent.parent
    model_path = assets_root / asset_rel
    if not model_path.exists():
        raise FileNotFoundError(f"Model not found for spec {spec_path}: {model_path}")

    module = torch.jit.load(str(model_path), map_location="cpu")
    avg, p50, p90 = benchmark_module(module, h, w, warmup, iterations)
    return BenchRow(
        model_id=spec["model_id"],
        asset_file=asset_rel,
        height=h,
        width=w,
        latency_avg_ms=avg,
        latency_p50_ms=p50,
        latency_p90_ms=p90,
        file_size_mb=file_size_mb(model_path),
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Benchmark delivered TorchScript models.")
    parser.add_argument("--model-spec", type=Path, action="append", required=True, help="Path to model_spec.json")
    parser.add_argument("--warmup", type=int, default=10)
    parser.add_argument("--iterations", type=int, default=40)
    parser.add_argument("--output", type=Path, default=Path("docs/benchmark_baseline.json"))
    args = parser.parse_args()

    rows: list[BenchRow] = []
    for spec in args.model_spec:
        row = run_one(spec, args.warmup, args.iterations)
        rows.append(row)
        print(
            f"{row.model_id}: avg={row.latency_avg_ms:.2f}ms p50={row.latency_p50_ms:.2f}ms "
            f"p90={row.latency_p90_ms:.2f}ms size={row.file_size_mb:.2f}MB"
        )

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps([asdict(r) for r in rows], indent=2), encoding="utf-8")
    print(f"\nWrote benchmark report: {args.output}")


if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""Compare App-exported mask PNG with algorithm reference mask (class index grayscale)."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

import numpy as np

try:
    from PIL import Image
except ImportError:
    print("Install: pip install pillow numpy", file=sys.stderr)
    raise


def load_class_mask(path: Path) -> np.ndarray:
    img = Image.open(path).convert("L")
    return np.array(img, dtype=np.int32)


def main() -> None:
    parser = argparse.ArgumentParser(description="Verify App mask vs reference mask_png.")
    parser.add_argument("--app-mask", type=Path, required=True)
    parser.add_argument("--ref-mask", type=Path, required=True)
    parser.add_argument("--min-iou", type=float, default=0.95)
    args = parser.parse_args()

    app = load_class_mask(args.app_mask)
    ref = load_class_mask(args.ref_mask)
    if app.shape != ref.shape:
        print(f"Shape mismatch: app {app.shape} vs ref {ref.shape}")
        sys.exit(1)

    accuracy = float((app == ref).mean())
    classes = np.unique(np.concatenate([app.ravel(), ref.ravel()]))
    ious = []
    for c in classes:
        a = app == c
        b = ref == c
        inter = np.logical_and(a, b).sum()
        union = np.logical_or(a, b).sum()
        if union > 0:
            ious.append(inter / union)
    mean_iou = float(np.mean(ious)) if ious else 1.0

    print(f"App mask: {args.app_mask}")
    print(f"Ref mask: {args.ref_mask}")
    print(f"Shape: {app.shape}")
    print(f"Pixel accuracy: {accuracy:.4f}")
    print(f"Mean class IoU: {mean_iou:.4f}")

    if mean_iou < args.min_iou:
        print(f"FAIL: mean IoU {mean_iou:.4f} < {args.min_iou}")
        sys.exit(1)
    print("PASS")


if __name__ == "__main__":
    main()

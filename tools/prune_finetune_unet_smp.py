#!/usr/bin/env python3
"""Structured prune and fine-tune for SMP UNet."""

from __future__ import annotations

import argparse
import json
import random
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import torch
import torch.nn as nn
import torch.nn.functional as F
import torch_pruning as tp
from PIL import Image
from segmentation_models_pytorch import Unet


@dataclass
class EvalResult:
    iou: float
    f1: float
    precision: float
    recall: float


def list_image_paths(folder: Path) -> list[Path]:
    exts = {".jpg", ".jpeg", ".png", ".bmp", ".webp"}
    return sorted([p for p in folder.iterdir() if p.is_file() and p.suffix.lower() in exts])


def load_rgb(path: Path, size: tuple[int, int], mean: list[float], std: list[float]) -> torch.Tensor:
    img = Image.open(path).convert("RGB").resize(size, Image.BILINEAR)
    arr = np.asarray(img, dtype=np.float32) / 255.0
    chw = np.transpose(arr, (2, 0, 1))
    mean_arr = np.asarray(mean, dtype=np.float32)[:, None, None]
    std_arr = np.asarray(std, dtype=np.float32)[:, None, None]
    norm = (chw - mean_arr) / std_arr
    return torch.from_numpy(norm)


def load_mask(path: Path, size: tuple[int, int]) -> torch.Tensor:
    mask = Image.open(path).convert("L").resize(size, Image.NEAREST)
    arr = np.asarray(mask, dtype=np.uint8)
    binary = (arr > 0).astype(np.float32)
    return torch.from_numpy(binary).unsqueeze(0)


class SegDataset(torch.utils.data.Dataset):
    def __init__(self, images_dir: Path, masks_dir: Path, width: int, height: int, mean: list[float], std: list[float]) -> None:
        self.images = list_image_paths(images_dir)
        if not self.images:
            raise ValueError(f"No images found in {images_dir}")
        self.pairs: list[tuple[Path, Path]] = []
        for img in self.images:
            mask = masks_dir / f"{img.stem}.png"
            if mask.exists():
                self.pairs.append((img, mask))
        if not self.pairs:
            raise ValueError(f"No paired masks found in {masks_dir} with stem-match rule.")
        self.size = (width, height)
        self.mean = mean
        self.std = std

    def __len__(self) -> int:
        return len(self.pairs)

    def __getitem__(self, index: int) -> tuple[torch.Tensor, torch.Tensor]:
        img_path, mask_path = self.pairs[index]
        x = load_rgb(img_path, self.size, self.mean, self.std)
        y = load_mask(mask_path, self.size)
        return x, y


def split_indices(n: int, val_ratio: float, seed: int) -> tuple[list[int], list[int]]:
    idx = list(range(n))
    random.Random(seed).shuffle(idx)
    val_n = max(1, int(n * val_ratio))
    val_idx = idx[:val_n]
    train_idx = idx[val_n:]
    if not train_idx:
        train_idx = val_idx
    return train_idx, val_idx


def build_model() -> nn.Module:
    return Unet(
        encoder_name="efficientnet-b0",
        encoder_weights=None,
        in_channels=3,
        classes=1,
        activation=None,
    )


def load_state(model: nn.Module, ckpt: Path) -> None:
    state = torch.load(ckpt, map_location="cpu")
    if isinstance(state, dict) and "state_dict" in state and isinstance(state["state_dict"], dict):
        state = state["state_dict"]
    model.load_state_dict(state, strict=False)


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


def freeze_stable_parts(model: nn.Module) -> None:
    frozen_prefixes = ("encoder._conv_stem", "encoder._bn0", "encoder._blocks.0", "encoder._blocks.1")
    for name, p in model.named_parameters():
        if name.startswith(frozen_prefixes):
            p.requires_grad = False


def metrics_from_logits(logits: torch.Tensor, targets: torch.Tensor, thr: float = 0.5) -> tuple[float, float, float, float]:
    probs = torch.sigmoid(logits)
    preds = (probs > thr).float()
    t = targets.float()
    tp = (preds * t).sum().item()
    fp = (preds * (1 - t)).sum().item()
    fn = ((1 - preds) * t).sum().item()

    precision = tp / (tp + fp + 1e-9)
    recall = tp / (tp + fn + 1e-9)
    iou = tp / (tp + fp + fn + 1e-9)
    f1 = 2 * precision * recall / (precision + recall + 1e-9)
    return iou, f1, precision, recall


def evaluate(model: nn.Module, loader: torch.utils.data.DataLoader, device: torch.device) -> EvalResult:
    model.eval()
    ious: list[float] = []
    f1s: list[float] = []
    ps: list[float] = []
    rs: list[float] = []
    with torch.no_grad():
        for x, y in loader:
            x = x.to(device)
            y = y.to(device)
            logits = model(x)
            if isinstance(logits, (list, tuple)):
                logits = logits[0]
            iou, f1, p, r = metrics_from_logits(logits, y)
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
    parser = argparse.ArgumentParser(description="Structured prune + quick fine-tune for UNet(EfficientNet-B0).")
    parser.add_argument("--checkpoint", type=Path, required=True)
    parser.add_argument("--model-spec", type=Path, required=True)
    parser.add_argument("--images-dir", type=Path, required=True)
    parser.add_argument("--masks-dir", type=Path, required=True)
    parser.add_argument("--steps", type=int, default=300)
    parser.add_argument("--batch-size", type=int, default=4)
    parser.add_argument("--lr", type=float, default=1e-4)
    parser.add_argument("--val-ratio", type=float, default=0.2)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--ratio-shallow", type=float, default=0.10)
    parser.add_argument("--ratio-mid", type=float, default=0.20)
    parser.add_argument("--ratio-deep", type=float, default=0.30)
    parser.add_argument("--output-checkpoint", type=Path, required=True)
    parser.add_argument("--output-torchscript", type=Path, required=True)
    args = parser.parse_args()

    spec = json.loads(args.model_spec.read_text(encoding="utf-8-sig"))
    h = int(spec["input"]["height"])
    w = int(spec["input"]["width"])
    mean = [float(v) for v in spec["input"]["mean"]]
    std = [float(v) for v in spec["input"]["std"]]

    torch.manual_seed(args.seed)
    random.seed(args.seed)
    np.random.seed(args.seed)

    dataset = SegDataset(args.images_dir, args.masks_dir, w, h, mean, std)
    train_idx, val_idx = split_indices(len(dataset), args.val_ratio, args.seed)
    train_set = torch.utils.data.Subset(dataset, train_idx)
    val_set = torch.utils.data.Subset(dataset, val_idx)
    train_loader = torch.utils.data.DataLoader(train_set, batch_size=args.batch_size, shuffle=True, num_workers=0)
    val_loader = torch.utils.data.DataLoader(val_set, batch_size=1, shuffle=False, num_workers=0)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    model = build_model()
    load_state(model, args.checkpoint)
    prune_model(model, h, w, args.ratio_shallow, args.ratio_mid, args.ratio_deep)
    freeze_stable_parts(model)
    model.to(device)

    optimizer = torch.optim.AdamW([p for p in model.parameters() if p.requires_grad], lr=args.lr)
    loss_fn = nn.BCEWithLogitsLoss()

    model.train()
    step = 0
    while step < args.steps:
        for x, y in train_loader:
            x = x.to(device)
            y = y.to(device)
            logits = model(x)
            if isinstance(logits, (list, tuple)):
                logits = logits[0]
            loss = loss_fn(logits, y)
            optimizer.zero_grad(set_to_none=True)
            loss.backward()
            optimizer.step()
            step += 1
            if step % 50 == 0 or step == 1:
                print(f"step={step}/{args.steps} loss={loss.item():.6f}")
            if step >= args.steps:
                break

    result = evaluate(model, val_loader, device)
    print(
        f"val iou={result.iou:.4f} f1={result.f1:.4f} "
        f"precision={result.precision:.4f} recall={result.recall:.4f}"
    )

    args.output_checkpoint.parent.mkdir(parents=True, exist_ok=True)
    torch.save(model.state_dict(), args.output_checkpoint)
    model_cpu = model.to("cpu").eval()
    traced = torch.jit.trace(model_cpu, torch.randn(1, 3, h, w))
    args.output_torchscript.parent.mkdir(parents=True, exist_ok=True)
    traced.save(str(args.output_torchscript))

    print(f"saved checkpoint: {args.output_checkpoint}")
    print(f"saved torchscript: {args.output_torchscript}")


if __name__ == "__main__":
    main()

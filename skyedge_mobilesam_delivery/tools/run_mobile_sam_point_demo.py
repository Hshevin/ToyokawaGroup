from __future__ import annotations

import argparse
import json
import shutil
from pathlib import Path
from typing import Any

import cv2
import matplotlib

matplotlib.use("Agg")

import matplotlib.pyplot as plt
import numpy as np
import torch
from kornia.contrib.visual_prompter import VisualPrompter
from kornia.models.sam.model import SamConfig
from PIL import Image


def load_rgb(path: Path) -> np.ndarray:
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.float32) / 255.0


def load_binary_mask(path: Path) -> np.ndarray:
    mask = np.asarray(Image.open(path).convert("L"), dtype=np.uint8)
    return mask > 127


def largest_component_prompt(mask: np.ndarray, use_box: bool = True) -> dict[str, Any]:
    num_labels, labels, stats, centroids = cv2.connectedComponentsWithStats(mask.astype(np.uint8), connectivity=8)
    if num_labels <= 1:
        h, w = mask.shape
        return {
            "positive_points": [[w // 2, h // 2]],
            "negative_points": [],
            "box": None,
        }

    component_id = 1 + int(np.argmax(stats[1:, cv2.CC_STAT_AREA]))
    cx, cy = centroids[component_id]
    ys, xs = np.where(labels == component_id)
    closest_idx = int(np.argmin((xs - cx) ** 2 + (ys - cy) ** 2))
    px = int(xs[closest_idx])
    py = int(ys[closest_idx])
    x = int(stats[component_id, cv2.CC_STAT_LEFT])
    y = int(stats[component_id, cv2.CC_STAT_TOP])
    w = int(stats[component_id, cv2.CC_STAT_WIDTH])
    h = int(stats[component_id, cv2.CC_STAT_HEIGHT])

    pad = max(8, int(max(w, h) * 0.08))
    x1 = max(0, x - pad)
    y1 = max(0, y - pad)
    x2 = min(mask.shape[1] - 1, x + w + pad)
    y2 = min(mask.shape[0] - 1, y + h + pad)

    prompt: dict[str, Any] = {
        "positive_points": [[px, py]],
        "negative_points": [],
        "box": [x1, y1, x2, y2] if use_box else None,
    }
    return prompt


def build_prompt_tensors(example: dict[str, Any], device: torch.device) -> tuple[torch.Tensor | None, torch.Tensor | None, torch.Tensor | None]:
    positive_points = example.get("positive_points", [])
    negative_points = example.get("negative_points", [])
    points = positive_points + negative_points
    labels = [1] * len(positive_points) + [0] * len(negative_points)

    keypoints = None
    keypoint_labels = None
    if points:
        keypoints = torch.tensor([points], dtype=torch.float32, device=device)
        keypoint_labels = torch.tensor([labels], dtype=torch.int64, device=device)

    box = example.get("box")
    boxes = None
    if box:
        boxes = torch.tensor([box], dtype=torch.float32, device=device)

    return keypoints, keypoint_labels, boxes


def crop_example_to_box(image: np.ndarray, example: dict[str, Any]) -> tuple[np.ndarray, dict[str, Any], tuple[int, int, int, int] | None]:
    box = example.get("box")
    if not example.get("crop_to_box", False) or not box:
        return image, example, None

    h, w = image.shape[:2]
    x1, y1, x2, y2 = [int(v) for v in box]
    x1 = max(0, min(w - 1, x1))
    y1 = max(0, min(h - 1, y1))
    x2 = max(x1 + 1, min(w, x2))
    y2 = max(y1 + 1, min(h, y2))
    cropped = image[y1:y2, x1:x2]

    def shift_points(points: list[list[int]]) -> list[list[int]]:
        shifted = []
        for x, y in points:
            px = int(x) - x1
            py = int(y) - y1
            if 0 <= px < cropped.shape[1] and 0 <= py < cropped.shape[0]:
                shifted.append([px, py])
        return shifted

    cropped_example = {
        **example,
        "positive_points": shift_points(example.get("positive_points", [])),
        "negative_points": shift_points(example.get("negative_points", [])),
        "box": None,
    }
    return cropped, cropped_example, (x1, y1, x2, y2)


def paste_crop_mask(mask: np.ndarray, full_shape: tuple[int, int], crop_box: tuple[int, int, int, int] | None) -> np.ndarray:
    if crop_box is None:
        return mask
    x1, y1, x2, y2 = crop_box
    full_mask = np.zeros(full_shape, dtype=bool)
    full_mask[y1:y2, x1:x2] = mask[: y2 - y1, : x2 - x1]
    return full_mask


def draw_prompt(ax: plt.Axes, image: np.ndarray, example: dict[str, Any]) -> None:
    ax.imshow(image)
    for x, y in example.get("positive_points", []):
        ax.scatter([x], [y], c="lime", s=80, marker="*", edgecolors="black", linewidths=1)
    for x, y in example.get("negative_points", []):
        ax.scatter([x], [y], c="red", s=80, marker="x", linewidths=2)
    box = example.get("box")
    if box:
        x1, y1, x2, y2 = box
        ax.add_patch(plt.Rectangle((x1, y1), x2 - x1, y2 - y1, fill=False, edgecolor="yellow", linewidth=2))
    ax.axis("off")
    ax.set_title("prompt")


def overlay_mask(image: np.ndarray, mask: np.ndarray, color: tuple[float, float, float] = (0.0, 0.8, 1.0)) -> np.ndarray:
    overlay = image.copy()
    color_arr = np.array(color, dtype=np.float32)
    overlay[mask] = overlay[mask] * 0.45 + color_arr * 0.55
    return np.clip(overlay, 0, 1)


def run_example(prompter: VisualPrompter, example: dict[str, Any], output_dir: Path) -> dict[str, Any]:
    image_path = Path(example["image"])
    image = load_rgb(image_path)

    reference_mask = None
    if example.get("reference_mask"):
        reference_mask = load_binary_mask(Path(example["reference_mask"]))
        if example.get("auto_prompt_from_reference", False):
            prompt = largest_component_prompt(reference_mask, use_box=example.get("use_box", True))
            example = {**example, **prompt}

    model_image, model_example, crop_box = crop_example_to_box(image, example)
    image_tensor = torch.from_numpy(model_image).permute(2, 0, 1).to(prompter.device)

    prompter.set_image(image_tensor)
    keypoints, keypoint_labels, boxes = build_prompt_tensors(model_example, prompter.device)
    multimask_output = bool(example.get("multimask_output", True))
    results = prompter.predict(keypoints, keypoint_labels, boxes=boxes, multimask_output=multimask_output)
    scores = results.scores[0].detach().cpu().numpy()
    masks = results.binary_masks[0].detach().cpu().numpy().astype(bool)
    best_idx = int(scores.argmax())
    best_mask = paste_crop_mask(masks[best_idx], image.shape[:2], crop_box)
    candidate_mask = paste_crop_mask(
        masks.reshape(-1, masks.shape[-2], masks.shape[-1]).max(axis=0),
        image.shape[:2],
        crop_box,
    )

    stem = example.get("name", image_path.stem)
    output_dir.mkdir(parents=True, exist_ok=True)
    Image.fromarray((best_mask * 255).astype(np.uint8)).save(output_dir / f"{stem}_mobile_sam_mask.png")
    Image.fromarray((overlay_mask(image, best_mask) * 255).astype(np.uint8)).save(output_dir / f"{stem}_overlay.png")

    fig, axes = plt.subplots(2, 3, figsize=(12, 8))
    axes[0, 0].imshow(image)
    axes[0, 0].axis("off")
    axes[0, 0].set_title("image")
    draw_prompt(axes[0, 1], image, example)
    if reference_mask is not None:
        axes[0, 2].imshow(reference_mask, cmap="gray")
        axes[0, 2].set_title("reference mask")
    else:
        axes[0, 2].axis("off")
        axes[0, 2].set_title("reference mask")

    axes[1, 0].imshow(best_mask, cmap="gray")
    axes[1, 0].axis("off")
    axes[1, 0].set_title(f"MobileSAM mask score={scores[best_idx]:.3f}")
    axes[1, 1].imshow(overlay_mask(image, best_mask))
    axes[1, 1].axis("off")
    axes[1, 1].set_title("overlay")
    axes[1, 2].imshow(candidate_mask, cmap="gray")
    axes[1, 2].axis("off")
    axes[1, 2].set_title("all candidates")
    for ax in axes.flat:
        ax.axis("off")
    fig.tight_layout()
    fig.savefig(output_dir / f"{stem}_comparison.png", dpi=160)
    plt.close(fig)

    return {
        "name": stem,
        "image": str(image_path),
        "positive_points": example.get("positive_points", []),
        "negative_points": example.get("negative_points", []),
        "box": example.get("box"),
        "crop_to_box": bool(example.get("crop_to_box", False)),
        "best_score": float(scores[best_idx]),
        "best_mask": str(output_dir / f"{stem}_mobile_sam_mask.png"),
        "comparison": str(output_dir / f"{stem}_comparison.png"),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Run an offline MobileSAM point-prompt correction demo.")
    parser.add_argument("--config", required=True)
    parser.add_argument("--out", default="outputs/mobile_sam_point_demo")
    parser.add_argument(
        "--checkpoint",
        default="models/mobile_sam_interactive_v1/mobile_sam.pt",
        help="Local MobileSAM checkpoint. It will be copied to torch hub cache before Kornia loads the pretrained model.",
    )
    args = parser.parse_args()

    config_path = Path(args.config)
    config = json.loads(config_path.read_text(encoding="utf-8"))
    output_dir = Path(args.out)
    checkpoint_path = Path(args.checkpoint)
    if checkpoint_path.exists():
        cache_path = Path.home() / ".cache" / "torch" / "hub" / "checkpoints" / "mobile_sam.pt"
        cache_path.parent.mkdir(parents=True, exist_ok=True)
        if not cache_path.exists() or cache_path.stat().st_size != checkpoint_path.stat().st_size:
            shutil.copy2(checkpoint_path, cache_path)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    prompter = VisualPrompter(
        SamConfig(model_type="mobile_sam", pretrained=True),
        device=device,
    )

    summaries = []
    for example in config["examples"]:
        summaries.append(run_example(prompter, example, output_dir))
        prompter.reset_image()

    (output_dir / "summary.json").write_text(json.dumps(summaries, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(summaries, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()

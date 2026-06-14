#!/usr/bin/env python3
"""Export MobileSAM encoder/decoder TorchScript bundles for Android PyTorch Mobile."""

from __future__ import annotations

import argparse
import json
import shutil
import sys
from pathlib import Path

import torch
import torch.nn as nn
import torch.nn.functional as F
from kornia.contrib.visual_prompter import VisualPrompter
from kornia.models.sam.model import SamConfig

TOOLS_DIR = Path(__file__).resolve().parent
ROOT = TOOLS_DIR.parent
DEFAULT_CKPT = ROOT / "skyedge_mobilesam_delivery/models/mobile_sam_interactive_v1/mobile_sam.pt"
DEFAULT_OUT = TOOLS_DIR / "out/mobile_sam"


def ensure_checkpoint(checkpoint: Path) -> None:
    cache_path = Path.home() / ".cache/torch/hub/checkpoints/mobile_sam.pt"
    cache_path.parent.mkdir(parents=True, exist_ok=True)
    if not cache_path.exists() or cache_path.stat().st_size != checkpoint.stat().st_size:
        shutil.copy2(checkpoint, cache_path)


class MobileSamEncoder(nn.Module):
    """Encode a SAM-preprocessed image tensor padded to encoder size."""

    def __init__(self, prompter: VisualPrompter) -> None:
        super().__init__()
        self.model = prompter.model

    def forward(self, padded_image: torch.Tensor) -> torch.Tensor:
        # padded_image: [1, 3, encoder_size, encoder_size], normalized RGB
        return self.model.image_encoder(padded_image)


class MobileSamDecoder(nn.Module):
    """Decode a mask from cached embeddings and point prompts in original-image coordinates."""

    def __init__(self, prompter: VisualPrompter) -> None:
        super().__init__()
        self.model = prompter.model
        self.encoder_size = int(prompter.model.image_encoder.img_size)

    def _transform_points(
        self,
        point_coords: torch.Tensor,
        orig_h: torch.Tensor,
        orig_w: torch.Tensor,
        resized_h: torch.Tensor,
        resized_w: torch.Tensor,
    ) -> torch.Tensor:
        scale_x = resized_w.float() / orig_w.float()
        scale_y = resized_h.float() / orig_h.float()
        transformed = point_coords.clone()
        transformed[..., 0] = transformed[..., 0] * scale_x
        transformed[..., 1] = transformed[..., 1] * scale_y
        return transformed

    def forward(
        self,
        image_embeddings: torch.Tensor,
        orig_h: torch.Tensor,
        orig_w: torch.Tensor,
        resized_h: torch.Tensor,
        resized_w: torch.Tensor,
        point_coords: torch.Tensor,
        point_labels: torch.Tensor,
    ) -> tuple[torch.Tensor, torch.Tensor]:
        # point_coords: [1, N, 2] in original image pixel space (x, y)
        transformed_points = self._transform_points(point_coords, orig_h, orig_w, resized_h, resized_w)
        sparse_embeddings, dense_embeddings = self.model.prompt_encoder(
            points=(transformed_points, point_labels),
            boxes=None,
            masks=None,
        )
        low_res_masks, iou_predictions = self.model.mask_decoder(
            image_embeddings=image_embeddings,
            image_pe=self.model.prompt_encoder.get_dense_pe(),
            sparse_prompt_embeddings=sparse_embeddings,
            dense_prompt_embeddings=dense_embeddings,
            multimask_output=True,
        )
        encoder_size = (self.encoder_size, self.encoder_size)
        masks = F.interpolate(low_res_masks, size=encoder_size, mode="bilinear", align_corners=False)
        masks = masks[:, :, : resized_h, : resized_w]
        best_idx = iou_predictions.argmax(dim=1)
        batch_idx = torch.arange(masks.shape[0], device=masks.device)
        best_mask = masks[batch_idx, best_idx]
        best_score = iou_predictions[batch_idx, best_idx]
        return best_mask, best_score


def build_prompter(checkpoint: Path, device: torch.device) -> VisualPrompter:
    ensure_checkpoint(checkpoint)
    prompter = VisualPrompter(SamConfig(model_type="mobile_sam", pretrained=True), device=device)
    prompter.model.eval()
    return prompter


def preprocess_for_mobile_sam(
    image: torch.Tensor,
    encoder_size: int,
    pixel_mean: torch.Tensor,
    pixel_std: torch.Tensor,
) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor, torch.Tensor]:
    """Match VisualPrompter preprocessing on the Python side for validation."""
    _, _, orig_h, orig_w = image.shape
    orig_h_t = torch.tensor(orig_h, dtype=torch.int64)
    orig_w_t = torch.tensor(orig_w, dtype=torch.int64)
    scale = float(encoder_size) / float(max(int(orig_h), int(orig_w)))
    resized_h = max(1, int(round(orig_h * scale)))
    resized_w = max(1, int(round(orig_w * scale)))
    resized = F.interpolate(image, size=(resized_h, resized_w), mode="bilinear", align_corners=False)
    mean = pixel_mean.view(1, 3, 1, 1)
    std = pixel_std.view(1, 3, 1, 1)
    normalized = (resized - mean) / std
    pad_h = encoder_size - resized_h
    pad_w = encoder_size - resized_w
    padded = F.pad(normalized, (0, pad_w, 0, pad_h))
    return (
        padded,
        orig_h_t,
        orig_w_t,
        torch.tensor(resized_h, dtype=torch.int64),
        torch.tensor(resized_w, dtype=torch.int64),
    )


def trace_modules(
    prompter: VisualPrompter,
    encoder_size: int,
    num_points: int = 1,
) -> tuple[torch.jit.ScriptModule, torch.jit.ScriptModule]:
    encoder = MobileSamEncoder(prompter).eval()
    decoder = MobileSamDecoder(prompter).eval()

    padded = torch.rand(1, 3, encoder_size, encoder_size)
    orig_h = torch.tensor(512, dtype=torch.int64)
    orig_w = torch.tensor(512, dtype=torch.int64)
    resized_h = torch.tensor(encoder_size, dtype=torch.int64)
    resized_w = torch.tensor(encoder_size, dtype=torch.int64)
    point_coords = torch.tensor([[[256.0, 256.0]]], dtype=torch.float32)
    point_labels = torch.ones(1, num_points, dtype=torch.int64)

    with torch.no_grad():
        embeddings = encoder(padded)
        mask, score = decoder(embeddings, orig_h, orig_w, resized_h, resized_w, point_coords, point_labels)

    traced_encoder = torch.jit.trace(encoder, (padded,), strict=False)
    traced_decoder = torch.jit.trace(
        decoder,
        (embeddings, orig_h, orig_w, resized_h, resized_w, point_coords, point_labels),
        strict=False,
    )

    with torch.no_grad():
        emb2 = traced_encoder(padded)
        mask2, score2 = traced_decoder(emb2, orig_h, orig_w, resized_h, resized_w, point_coords, point_labels)
        max_diff = (mask - mask2).abs().max().item()
        if max_diff > 1e-3:
            print(f"[warn] traced decoder max diff={max_diff:.6f}")

    return traced_encoder, traced_decoder


def validate_against_visual_prompter(
    prompter: VisualPrompter,
    traced_encoder: torch.jit.ScriptModule,
    traced_decoder: torch.jit.ScriptModule,
    image_path: Path,
    point_xy: tuple[float, float],
) -> dict:
    from PIL import Image
    import numpy as np

    rgb = np.asarray(Image.open(image_path).convert("RGB"), dtype=np.float32) / 255.0
    image = torch.from_numpy(rgb).permute(2, 0, 1).unsqueeze(0)
    padded, oh, ow, rh, rw = preprocess_for_mobile_sam(
        image,
        int(prompter.model.image_encoder.img_size),
        prompter.pixel_mean,
        prompter.pixel_std,
    )
    point_coords = torch.tensor([[[point_xy[0], point_xy[1]]]], dtype=torch.float32)
    point_labels = torch.tensor([[1]], dtype=torch.int64)

    with torch.no_grad():
        emb = traced_encoder(padded)
        mask_logits, score = traced_decoder(emb, oh, ow, rh, rw, point_coords, point_labels)
        mask_resized = mask_logits[0] > 0.0
        mask_ts = F.interpolate(
            mask_resized.unsqueeze(0).unsqueeze(0).float(),
            size=(int(image.shape[2]), int(image.shape[3])),
            mode="bilinear",
            align_corners=False,
        )[0, 0] > 0.0

    prompter.reset_image()
    prompter.set_image(image[0])
    ref = prompter.predict(point_coords, point_labels, boxes=None, multimask_output=True)
    ref_mask = ref.binary_masks[0, ref.scores[0].argmax().item()].cpu()

    iou = ((mask_ts & ref_mask).sum().float() / (mask_ts | ref_mask).sum().float()).item()
    return {
        "image": str(image_path),
        "point": list(point_xy),
        "trace_score": float(score.item()),
        "reference_score": float(ref.scores[0].max().item()),
        "mask_iou": float(iou),
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Export MobileSAM TorchScript encoder/decoder.")
    parser.add_argument("--checkpoint", type=Path, default=DEFAULT_CKPT)
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT)
    parser.add_argument("--encoder-size", type=int, default=1024)
    parser.add_argument("--validate-demo", action="store_true")
    args = parser.parse_args()

    if not args.checkpoint.exists():
        raise SystemExit(f"Checkpoint not found: {args.checkpoint}")

    device = torch.device("cpu")
    prompter = build_prompter(args.checkpoint, device)
    encoder_size = int(prompter.model.image_encoder.img_size)
    traced_encoder, traced_decoder = trace_modules(prompter, encoder_size)

    args.out_dir.mkdir(parents=True, exist_ok=True)
    encoder_path = args.out_dir / "mobile_sam_encoder.pt"
    decoder_path = args.out_dir / "mobile_sam_decoder.pt"
    traced_encoder.save(str(encoder_path))
    traced_decoder.save(str(decoder_path))

    manifest = {
        "encoder_asset": encoder_path.name,
        "decoder_asset": decoder_path.name,
        "encoder_size": encoder_size,
        "input_layout": "NCHW padded normalized RGB",
        "encoder_mb": round(encoder_path.stat().st_size / (1024 * 1024), 2),
        "decoder_mb": round(decoder_path.stat().st_size / (1024 * 1024), 2),
    }

    if args.validate_demo:
        demo_image = ROOT / "skyedge_mobilesam_delivery/demo/test_images/building/building_demo_image.png"
        demo_point = (99.0, 14.0)
        if demo_image.exists():
            manifest["validation"] = validate_against_visual_prompter(
                prompter,
                traced_encoder,
                traced_decoder,
                demo_image,
                demo_point,
            )

    manifest_path = args.out_dir / "export_manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False), encoding="utf-8")
    print(json.dumps(manifest, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()

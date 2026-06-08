"""FP8 weight-only quantization helpers."""

from __future__ import annotations

import json
import struct
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

import torch
import torch.nn as nn
import torch.nn.functional as F

FP8_PACK_MAGIC = b"SKFP8\x01"


@dataclass(frozen=True)
class Fp8QuantConfig:
    fmt: str = "e4m3fn"
    granularity: str = "per_tensor"
    skip_prefixes: tuple[str, ...] = ("encoder._conv_stem", "segmentation_head")


def fp8_dtype(name: str) -> torch.dtype:
    if name == "e4m3fn":
        return torch.float8_e4m3fn
    if name == "e5m2":
        return torch.float8_e5m2
    raise ValueError(f"Unsupported fp8 format: {name}")


def should_skip_module(name: str, skip_prefixes: Iterable[str]) -> bool:
    return any(name.startswith(prefix) for prefix in skip_prefixes)


def quantize_tensor_to_fp8(
    weight: torch.Tensor,
    dtype: torch.dtype,
    granularity: str,
) -> tuple[torch.Tensor, torch.Tensor]:
    w = weight.detach().float()
    finfo = torch.finfo(dtype)

    if granularity == "per_tensor":
        max_val = w.abs().max()
        if max_val == 0:
            scale = torch.tensor(1.0, dtype=torch.float32)
            codes = torch.zeros_like(w, dtype=torch.uint8)
            return codes, scale
        scale = (max_val / finfo.max).to(torch.float32)
        q = (w / scale).clamp(finfo.min, finfo.max).to(dtype)
        return q.view(torch.uint8), scale.reshape(())

    if granularity == "per_channel":
        if w.ndim == 4:
            reduce_dims = (1, 2, 3)
        elif w.ndim == 2:
            reduce_dims = (1,)
        else:
            return quantize_tensor_to_fp8(w, dtype, "per_tensor")
        max_val = w.abs().amax(dim=reduce_dims, keepdim=True)
        max_val = torch.clamp(max_val, min=1e-12)
        scale = (max_val / finfo.max).to(torch.float32)
        q = (w / scale).clamp(finfo.min, finfo.max).to(dtype)
        return q.view(torch.uint8), scale.squeeze()

    raise ValueError(f"Unsupported granularity: {granularity}")


def dequantize_fp8(codes: torch.Tensor, scales: torch.Tensor, dtype: torch.dtype, like: torch.Tensor) -> torch.Tensor:
    q = codes.view(dtype).to(torch.float32)
    if scales.ndim == 0:
        return q * scales
    while scales.ndim < q.ndim:
        scales = scales.unsqueeze(-1)
    return q * scales


def apply_fp8_weight_only(
    model: nn.Module,
    config: Fp8QuantConfig,
) -> tuple[int, int]:
    dtype = fp8_dtype(config.fmt)
    quantized_layers = 0
    skipped_layers = 0
    for name, module in model.named_modules():
        if should_skip_module(name, config.skip_prefixes):
            if isinstance(module, (nn.Conv2d, nn.Linear, nn.ConvTranspose2d)):
                skipped_layers += 1
            continue
        if isinstance(module, (nn.Conv2d, nn.Linear, nn.ConvTranspose2d)):
            if module.weight is None:
                skipped_layers += 1
                continue
            codes, scales = quantize_tensor_to_fp8(module.weight, dtype, config.granularity)
            with torch.no_grad():
                module.weight.copy_(
                    dequantize_fp8(codes, scales, dtype, module.weight).to(module.weight.dtype)
                )
            quantized_layers += 1
    return quantized_layers, skipped_layers


def iter_quantizable_weights(model: nn.Module, skip_prefixes: Iterable[str]) -> Iterable[tuple[str, torch.Tensor]]:
    for name, tensor in model.state_dict().items():
        if not name.endswith(".weight"):
            continue
        module_name = name[: -len(".weight")]
        if should_skip_module(module_name, skip_prefixes):
            continue
        if tensor.ndim >= 2:
            yield name, tensor


def export_fp8_pack(model: nn.Module, config: Fp8QuantConfig, output_path: Path) -> dict:
    dtype = fp8_dtype(config.fmt)
    layers: list[dict] = []
    blob = bytearray()

    for name, weight in iter_quantizable_weights(model, config.skip_prefixes):
        codes, scales = quantize_tensor_to_fp8(weight, dtype, config.granularity)
        codes_bytes = codes.contiguous().cpu().numpy().tobytes()
        scales_bytes = scales.contiguous().cpu().numpy().astype("float32").tobytes()
        entry = {
            "name": name,
            "shape": list(weight.shape),
            "codes_offset": len(blob),
            "codes_len": len(codes_bytes),
            "scales_offset": len(blob) + len(codes_bytes),
            "scales_len": len(scales_bytes),
            "granularity": config.granularity,
        }
        blob.extend(codes_bytes)
        blob.extend(scales_bytes)
        layers.append(entry)

    manifest = {
        "magic": FP8_PACK_MAGIC.decode("latin1"),
        "version": 1,
        "fp8_format": config.fmt,
        "granularity": config.granularity,
        "skip_prefixes": list(config.skip_prefixes),
        "layer_count": len(layers),
        "layers": layers,
        "blob_size_bytes": len(blob),
    }
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("wb") as f:
        f.write(FP8_PACK_MAGIC)
        manifest_json = json.dumps(manifest, ensure_ascii=False).encode("utf-8")
        f.write(struct.pack("<I", len(manifest_json)))
        f.write(manifest_json)
        f.write(blob)
    return manifest


def load_fp8_pack_into_model(model: nn.Module, pack_path: Path) -> Fp8QuantConfig:
    data = pack_path.read_bytes()
    if not data.startswith(FP8_PACK_MAGIC):
        raise ValueError(f"Not an FP8 pack file: {pack_path}")
    offset = len(FP8_PACK_MAGIC)
    (manifest_len,) = struct.unpack_from("<I", data, offset)
    offset += 4
    manifest = json.loads(data[offset : offset + manifest_len].decode("utf-8"))
    offset += manifest_len
    blob = data[offset:]
    config = Fp8QuantConfig(
        fmt=manifest["fp8_format"],
        granularity=manifest["granularity"],
        skip_prefixes=tuple(manifest.get("skip_prefixes", ())),
    )
    dtype = fp8_dtype(config.fmt)
    state = model.state_dict()
    for layer in manifest["layers"]:
        name = layer["name"]
        if name not in state:
            continue
        codes_bytes = blob[layer["codes_offset"] : layer["codes_offset"] + layer["codes_len"]]
        scales_bytes = blob[layer["scales_offset"] : layer["scales_offset"] + layer["scales_len"]]
        codes = torch.frombuffer(bytearray(codes_bytes), dtype=torch.uint8).reshape(layer["shape"])
        scale_count = len(scales_bytes) // 4
        if scale_count == 1:
            scales = torch.frombuffer(bytearray(scales_bytes), dtype=torch.float32)
        else:
            scales = torch.frombuffer(bytearray(scales_bytes), dtype=torch.float32).reshape(scale_count)
        state[name] = dequantize_fp8(codes, scales, dtype, state[name]).to(state[name].dtype)
    model.load_state_dict(state, strict=False)
    return config


class FP8Conv2d(nn.Module):
    def __init__(self, conv: nn.Conv2d, config: Fp8QuantConfig) -> None:
        super().__init__()
        dtype = fp8_dtype(config.fmt)
        codes, scales = quantize_tensor_to_fp8(conv.weight, dtype, config.granularity)
        self.register_buffer("weight_fp8", codes.contiguous())
        self.register_buffer("weight_scale", scales.contiguous())
        self.fp8_format = config.fmt
        self.granularity = config.granularity
        if conv.bias is not None:
            self.bias = nn.Parameter(conv.bias.detach().clone())
        else:
            self.bias = None
        self.stride = conv.stride
        self.padding = conv.padding
        self.dilation = conv.dilation
        self.groups = conv.groups

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        weight = dequantize_fp8(
            self.weight_fp8,
            self.weight_scale,
            fp8_dtype(self.fp8_format),
            x,
        )
        return F.conv2d(x, weight, self.bias, self.stride, self.padding, self.dilation, self.groups)


class FP8Linear(nn.Module):
    def __init__(self, linear: nn.Linear, config: Fp8QuantConfig) -> None:
        super().__init__()
        dtype = fp8_dtype(config.fmt)
        codes, scales = quantize_tensor_to_fp8(linear.weight, dtype, config.granularity)
        self.register_buffer("weight_fp8", codes.contiguous())
        self.register_buffer("weight_scale", scales.contiguous())
        self.fp8_format = config.fmt
        self.granularity = config.granularity
        if linear.bias is not None:
            self.bias = nn.Parameter(linear.bias.detach().clone())
        else:
            self.bias = None

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        weight = dequantize_fp8(
            self.weight_fp8,
            self.weight_scale,
            fp8_dtype(self.fp8_format),
            x,
        )
        return F.linear(x, weight, self.bias)


def replace_with_fp8_wrappers(model: nn.Module, config: Fp8QuantConfig) -> tuple[int, int]:
    replaced = 0
    skipped = 0

    def visit(parent: nn.Module, prefix: str) -> None:
        nonlocal replaced, skipped
        for child_name, child in list(parent.named_children()):
            full_name = f"{prefix}.{child_name}" if prefix else child_name
            if isinstance(child, (FP8Conv2d, FP8Linear)):
                continue
            if should_skip_module(full_name, config.skip_prefixes):
                if isinstance(child, (nn.Conv2d, nn.Linear)):
                    skipped += 1
                visit(child, full_name)
                continue
            if isinstance(child, nn.Conv2d):
                setattr(parent, child_name, FP8Conv2d(child, config))
                replaced += 1
                continue
            if isinstance(child, nn.Linear):
                setattr(parent, child_name, FP8Linear(child, config))
                replaced += 1
                continue
            visit(child, full_name)

    visit(model, "")
    return replaced, skipped


def pack_file_size_mb(path: Path) -> float:
    return path.stat().st_size / (1024 * 1024)

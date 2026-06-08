Active models (referenced by model_spec.json):

  building_unet_efficientnetb0_v1_pruned_fp8.fp8pkg
  building_unet_efficientnetb0_v1_pruned_fp8_runtime.pt
  road_unet_efficientnetb0_v1_fp8.fp8pkg
  road_unet_efficientnetb0_v1_fp8_runtime.pt

Tooling only (not loaded by App):

  building_unet_efficientnetb0_v1_pruned_ft_s2.pth  — re-run tune_fp8_unet.py --prune

Regenerate with:

  py -3 tools/tune_fp8_unet.py --prune --output-dir tools/out/fp8_tune/building_pruned ...

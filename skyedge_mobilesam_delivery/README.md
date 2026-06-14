# SkyEdge MobileSAM Delivery

本目录为 MobileSAM 交互式修正能力交付包，结构和此前建筑/道路模型交付包保持一致，供前后端或虚拟机同学离线验证。

## 一、定位说明

MobileSAM 不是本项目的主自动识别模型。当前项目主线仍是：

- 建筑物识别：`building_unet_efficientnetb0_v1`
- 道路面状区域识别：`road_unet_efficientnetb0_v1`

MobileSAM 在本项目中作为 P2 辅助能力，用于用户点选或框选后的局部 mask 修正。典型用途包括：

- 建筑物识别结果边界不准时，用户框选局部建筑，MobileSAM 辅助重新生成局部 mask。
- 道路识别结果断裂或局部误识别时，用户选择局部 ROI，MobileSAM 辅助补充或删除局部区域。
- 灾害范围人工校正时，作为边界辅助分割工具，但最终范围仍以用户 GPS 轨迹和人工确认结果为准。

## 二、目录结构

```text
skyedge_mobilesam_delivery/
├─ README.md
├─ requirements.txt
├─ models/
│  └─ mobile_sam_interactive_v1/
│     ├─ mobile_sam.pt
│     ├─ model_spec.json
│     └─ metrics.json
├─ tools/
│  └─ run_mobile_sam_point_demo.py
└─ demo/
   ├─ demo_points.json
   ├─ test_images/
   │  ├─ building/
   │  │  ├─ building_demo_image.png
   │  │  └─ building_demo_reference_mask.png
   │  └─ road/
   │     ├─ road_demo_image.png
   │     └─ road_demo_reference_mask.png
   └─ outputs/
      ├─ building_demo_comparison.png
      ├─ building_demo_mobile_sam_mask.png
      ├─ building_demo_overlay.png
      ├─ road_demo_comparison.png
      ├─ road_demo_mobile_sam_mask.png
      ├─ road_demo_overlay.png
      └─ summary.json
```

## 三、输入输出约定

输入图像：

- RGB 三通道图像
- 像素坐标按 `(x, y)` 记录
- 支持正样本点、负样本点和可选 ROI 框

Prompt 示例：

```json
{
  "image": "demo/test_images/building/building_demo_image.png",
  "positive_points": [[99, 14]],
  "negative_points": [],
  "box": [23, 0, 164, 63],
  "crop_to_box": true,
  "mode": "replace"
}
```

输出文件：

- `*_mobile_sam_mask.png`：单通道二值 mask，目标区域为 255，背景为 0
- `*_overlay.png`：mask 叠加原图的预览图
- `*_comparison.png`：原图、prompt、参考 mask、MobileSAM 输出对比图
- `summary.json`：demo 运行结果和 best score

## 四、虚拟机验证命令

在该目录下安装依赖：

```powershell
pip install -r requirements.txt
```

运行 demo：

```powershell
python tools/run_mobile_sam_point_demo.py --config demo/demo_points.json --out demo/run_outputs --checkpoint models/mobile_sam_interactive_v1/mobile_sam.pt
```

脚本会先把包内 `mobile_sam.pt` 复制到 PyTorch hub cache：

```text
%USERPROFILE%\.cache\torch\hub\checkpoints\mobile_sam.pt
```

然后通过 Kornia 的 `VisualPrompter(SamConfig(model_type="mobile_sam", pretrained=True))` 加载模型。

## 五、当前 demo 结果

| 示例 | 任务 | Prompt | Best score | 输出 |
|---|---|---|---:|---|
| building_demo | 建筑局部修正 | 1 个正样本点 + ROI 框 | 0.9738 | `demo/outputs/building_demo_comparison.png` |
| road_demo | 道路局部修正 | 1 个正样本点 + ROI 框 | 0.9331 | `demo/outputs/road_demo_comparison.png` |

## 六、端侧集成建议

前端传入用户点选/框选数据，端侧或 Python 服务端按以下流程处理：

1. 原始建筑/道路模型先生成初始 mask。
2. 用户在局部区域点选或框选需要修正的对象。
3. MobileSAM 只在局部 ROI 内推理，输出新的局部 mask。
4. 按 `mode` 合并结果：
   - `replace`：替换该 ROI 内原 mask
   - `add`：把新 mask 加入原 mask
   - `delete`：从原 mask 中删除该区域
5. 保存修正后的 mask，并同步更新建筑/道路对象或报告附件。

## 七、注意事项

- MobileSAM 是预训练交互式分割模型，本项目没有对其重新训练。
- `metrics.json` 中的 score 是 demo prompt 分数，不是正式数据集 IoU/F1。
- MobileSAM 不负责判断“违建”“新建”“损毁”等业务结论，这些仍由用户人工标注。
- 正式移动端集成时，可继续评估 ONNX、ExecuTorch 或服务端轻量推理方案。

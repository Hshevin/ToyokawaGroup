# 端侧模型优化交付报告（剪枝 / 量化）

更新时间：2026-06-04  
负责人：端侧模型部署（你）  
覆盖模型：

- `building_unet_efficientnetb0_v1`
- `road_unet_efficientnetb0_v1`

---

## 1. 本轮目标与边界

### 目标

在不改动 App 业务逻辑的前提下，评估并产出可替换 baseline 的优化模型版本，重点关注：

- 模型体积下降
- 推理时延下降
- 精度不明显劣化（IoU 可接受）

### 边界

- 本轮工作仅覆盖**模型优化链路**（剪枝/量化/导出/评估）。
- 不涉及 UI、数据库、接口协议改动。

---

## 2. 基线与门禁标准

当前基线（baseline）：

- building: `building_unet_efficientnetb0_v1_torchscript.pt`
- road: `road_unet_efficientnetb0_v1_torchscript.pt`

替换 baseline 必须同时满足三项门禁：

1. 体积门禁：`size_ratio <= 0.70`（至少缩小 30%）
2. 时延门禁：`latency_ratio <= 0.80`（至少快 20%）
3. 精度门禁：`iou_drop_pct <= 1.50`

说明：`size_ratio/latency_ratio/iou_drop_pct` 均按各自目标模型对应 baseline 计算。

---

## 3. 实验数据与环境

### 数据

项目内测试集：`skyedge_vm_test_images`

- `building/images + building/masks`：10 对
- `road/images + road/masks`：10 对

### 环境

- Python 3.14
- PyTorch 2.12
- `segmentation-models-pytorch`
- `torch-pruning`

### 评估流程

1. 从 `best_model.pth` 重建 `UNet(efficientnet-b0)`。
2. 执行结构化剪枝（channel-level，L1 importance）。
3. 可选：300 step 快速微调（lr=1e-4）。
4. 导出 `.pt`，统计体积和延迟。
5. 按门禁规则自动判定。

---

## 4. 关键实验结果（摘要）

### 4.1 量化路线结论

#### Dynamic INT8（已废弃）

- 动态量化（linear-only）在本模型结构上**无收益**：
  - 体积不降（约持平甚至略增）
  - 时延无明显改善（部分场景更慢）

结论：不采用 dynamic int8。

#### FP8 调参结果（真正实现体积压缩）

工具：`tools/tune_fp8_unet.py`（12 组网格搜索）+ `tools/fp8_quant_utils.py`（FP8 打包 `.fp8pkg`）

**最优配置（building / road 一致）**：

| 参数 | 值 |
|------|-----|
| 格式 | **E4M3**（E5M2 掉点过大） |
| 粒度 | **per_tensor** |
| 跳过层 | stem + segmentation_head + **decoder**（decoder 保持 FP32） |

| 指标 | FP32 baseline `.pt` | **FP8 `.fp8pkg`** | 运行时 `_runtime.pt` |
|------|---------------------|-------------------|---------------------|
| 体积 | ~22.3 MB | **~3.79 MB（17%）** | ~24.6 MB（推理用） |
| Building IoU 掉点 | — | **0.46%** | — |
| Road IoU 掉点 | — | **-0.77%**（略升） | — |

**App 接入**：`model_spec.json` 的 `asset_file` 指向 `*.fp8pkg`；`ModelLoader` 自动加载同目录 `{name}_runtime.pt` 推理。

报告：`docs/fp8_tune_building.json`、`docs/fp8_tune_road.json`

**Building 剪枝 S2 + FP8（推荐接入）** — `docs/fp8_tune_building_pruned.json`：

| 指标 | 剪枝 S2 FP32 | 剪枝 + FP8 |
|------|--------------|------------|
| IoU 掉点（相对剪枝 baseline） | — | **0.72%** |
| `.fp8pkg` | — | **2.38 MB** |
| `_runtime.pt` | ~15.2 MB | ~15.2 MB |

App building 已指向 `building_unet_efficientnetb0_v1_pruned_fp8.fp8pkg`（剪枝与 FP8 叠加）。

Road 仅 FP8（剪枝实验未通过，无法叠加）。

### 4.2 剪枝路线结论（building）

- S1（0.10/0.20/0.30）+ 300 step 微调：
  - 体积与时延显著提升
  - IoU 掉点 `1.78%`，略超门禁（未过）
- S2（0.08/0.15/0.25）+ 300 step 微调：
  - `size_ratio = 0.684`（过）
  - `latency_ratio = 0.720`（过）
  - `iou_drop_pct = -0.35`（过）
  - `pass_all_gate = true`

结论：building 已得到可替换 baseline 的优化版本。

### 4.3 剪枝路线结论（road）

- S1（0.10/0.20/0.30）+ 300 step：IoU/F1 接近 0（塌陷）
- S2（0.05/0.10/0.15）+ 300 step：仍塌陷

结论：road 在“10 对样本 + 300 step”条件下，剪枝后稳定性不足，当前不具备替换 baseline 条件。

---

## 5. 本轮可交付产物

### 推荐接入（通过门禁）

- building 剪枝版（推荐候选）：`building_unet_efficientnetb0_v1_pruned_ft_s2.pt`（实验产物，需放入 `assets` 并在对应 `model_spec.json` 的 `asset_file` 中指向）

### 不建议接入（未通过门禁）

- road 剪枝版本（含 S1/S2）
- dynamic int8 版本（building/road）

### 当前 App 默认加载

- building / road 均使用 `models/<model_id>/*_torchscript.pt`（baseline）

---

## 6. 决策建议（给产品/算法/端侧）

### 立即决策（可执行）

1. `building`：允许进入灰度验证（候选为 `pruned_ft_s2.pt`）。
2. `road`：保持 baseline，不切换。
3. `dynamic quant`：本轮结论为无收益，暂不继续消耗时间。

### 后续迭代建议

road 若要继续优化，建议至少满足以下任一条件再开新轮：

- 提供更大且更稳定的 road 微调样本（明显高于 10 对）
- 增加微调步数（500~1500）并引入更稳训练策略（如 loss reweight / lr schedule）
- 算法侧直接提供 `road` 的 pruned+finetuned 成品权重

---

## 7. 与算法侧对接要点

- 端侧：维护剪枝/验收脚本，给出是否可替换 baseline 的结论
- 算法：road 优化需更充分样本或成品权重；各版本附 IoU/F1 与 `model_spec.json`

---

## 8. 附录：相关脚本

- 基线测速：`tools/benchmark_torchscript_models.py`
- 剪枝快跑：`tools/structured_prune_unet_smp.py`
- 剪枝+微调：`tools/prune_finetune_unet_smp.py`
- mask 验收：`tools/verify_mask.py`

---

## 9. 一句话总结

本轮优化已产出明确结果：**building 可优化上线，road 暂不具备替换条件，建议保持 baseline 并等待下一轮更充分数据/权重支持。**

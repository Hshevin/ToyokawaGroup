# SkyEdge 功能规划 TODO

本文档记录**未来功能**的实现规划，按阶段与模块拆分。已完成能力见 [`README.md`](../README.md)；架构边界见 [`ARCHITECTURE.md`](ARCHITECTURE.md)；字段契约见 [`openapi/api.yaml`](../openapi/api.yaml)。

**原则**：App 运行时推理、存储、报告生成均不依赖远程 HTTP 后端（地图 SDK 瓦片除外）。需跨设备/跨人员协同的能力单独标注，并给出端侧替代方案。

---

## 状态图例

- `[x]` 已完成（Phase 0）
- `[ ]` 待实现
- `[-]` 暂缓 / 需决策

---

## Phase 0 — 基线（已完成）

- [x] 相册选图 + Building / Road 端侧分割（PyTorch Mobile，512×512）
- [x] 原图与 mask 叠加并排预览（`InspectionScreen`）
- [x] GeoTIFF 导入 + WGS84 → GCJ-02 + 高德卫星 `GroundOverlay`（`MapScreen`）
- [x] mask 图层开关、透明度调节（`setMapLayerVisibility` / `setMaskAlpha`）
- [x] mask 回映射至 preview 尺寸，生成 `mask_overlay.png`
- [x] Room 落库：`ImageRecordRepository`、`summary_json`、`refreshHistory()`
- [x] OpenAPI v0.1 字段约定；v0.2 异常/报告 schema 定义（端侧未实现）

---

## Phase 1 — 第一版交互（MVP）

目标：支撑**场景一（违建识别）**的最小闭环——加载 → 识别 → 人工框选标注 → 拍照 → 导出基础报告。

### 1.1 影像导入与入口

- [ ] **P1-IMPORT-01** 统一影像来源入口：相册、无人机截图、内置样例图
  - 模块：`skyedge-ui`（选图 UI）、`app`（权限）
  - 说明：样例图放 `assets/sample_images/`，免权限演示

- [ ] **P1-IMPORT-02** 任务/会话概念落地（对齐 OpenAPI `Task`）
  - 模块：`imgrecord`（新表或扩展）、`skyedge-core`（`InspectionFacade`）
  - 说明：一次巡检对应一个 `taskId`，多张图挂在任务下

### 1.2 人工框选与记录卡片

- [ ] **P1-ANNOTATE-01** 框选手势：在原图/mask 画布上绘制归一化 `BoundingBox`
  - 模块：`skyedge-ui`（手势层）
  - 契约：`openapi` → `BoundingBox`（x/y/width/height，0~1）

- [ ] **P1-ANNOTATE-02** 记录卡片 UI：类型、严重程度、备注、确认/驳回
  - 模块：`skyedge-ui`
  - 契约：`Anomaly`、`ReviewAnomalyRequest`、`ReviewStatus`

- [ ] **P1-ANNOTATE-03** `InspectionFacade` 扩展：提交/更新/列出异常卡片
  - 模块：`skyedge-core/api`、`InspectionFacadeImpl`
  - 建议 API：`submitAnomaly(...)`、`reviewAnomaly(id, status, comment)`、`listAnomalies(sessionId)`

- [ ] **P1-ANNOTATE-04** 异常数据落库（Room）
  - 模块：`imgrecord`
  - 说明：独立 `Anomaly` 表，关联 `local_url` / `taskId`；不全部塞进 `summary_json`

### 1.3 mask 实例化（简化版）

- [ ] **P1-MASK-01** 连通域拆分：整图 mask → 多个 bbox 候选
  - 模块：`skyedge-core`（后处理）
  - 说明：第一版可用 OpenCV/Bitmap 扫描，不必等 MobileSAM

- [ ] **P1-MASK-02** 每个实例自动生成卡片草稿（`reviewStatus=pending`）
  - 模块：`skyedge-core` + `skyedge-ui`
  - 说明：用户可确认违建或驳回

### 1.4 现场拍照存档

- [ ] **P1-CAMERA-01** 核查流程内调起相机拍照
  - 模块：`skyedge-ui`、`app`（`CAMERA` 权限）

- [ ] **P1-CAMERA-02** 照片与任务/异常卡片绑定，落盘至会话目录
  - 模块：`imgrecord`、`InspectionFacadeImpl`
  - 路径建议：`filesDir/analysis/<uuid>/photos/<anomalyId>.jpg`

### 1.5 离线报告导出（最小形态）

- [ ] **P1-REPORT-01** 长图报告：原图 + mask 叠加 + 标注框截图拼接
  - 模块：`skyedge-core`（合成）、`skyedge-ui`（预览/分享）

- [ ] **P1-REPORT-02** JSON 结构化导出：异常列表、核查结论、时间/地理元数据
  - 模块：`skyedge-core`
  - 契约：对齐 `ReportSummary`、`Anomaly`

- [ ] **P1-REPORT-03** 导出到相册 / 系统分享（`MediaStore` / `ACTION_SEND`）
  - 模块：`skyedge-ui`、`app`

- [ ] **P1-REPORT-04** `InspectionFacade.exportReport(formats)` 端侧实现
  - 模块：`skyedge-core`
  - 契约：对齐 `CreateReportRequest`、`ReportFormat`（第一版仅 `image` + `json`）

### 1.6 场景一串联验收

- [ ] **P1-E2E-01** 违建识别端到端：定位元数据（GeoTIFF bounds）→ 加载 → Building 推理 → 卡片确认 → 拍照 → 导出
  - 依赖：P1-IMPORT、P1-ANNOTATE、P1-MASK、P1-CAMERA、P1-REPORT

---

## Phase 2 — 场景深化

目标：双时相对比、精细交互、定位绑定、结构化报告完善。

### 2.1 历史 vs 本次影像对比

- [ ] **P2-COMPARE-01** 双图会话：选择历史记录 + 本次新图
  - 模块：`skyedge-ui`、`InspectionFacade`
  - 契约：`LayerBundle`（`original` + `change`）

- [ ] **P2-COMPARE-02** 移动端卷帘对比（划屏/拖动切换两时相）
  - 模块：`skyedge-ui`

- [ ] **P2-COMPARE-03** 变化图层生成（像素差分或算法侧 change mask）
  - 模块：`skyedge-core`、算法交付
  - 说明：滞后性大时需 UI 提示「时相差过久，仅供参考」

### 2.2 点选交互与 MobileSAM

- [ ] **P2-SAM-01** 算法交付 MobileSAM TorchScript（`08_mobile_sam`）
  - 模块：算法侧 → `skyedge-core/assets`

- [ ] **P2-SAM-02** 点选坐标传入推理引擎，refine 单实例 mask
  - 模块：`skyedge-core/model`、`InspectionFacade`

- [ ] **P2-SAM-03** 点选与 mask 卡片联动（点击 mask 高亮对应卡片）
  - 模块：`skyedge-ui`

### 2.3 手机 GPS 定位

- [ ] **P2-GPS-01** 获取当前 GPS，写入任务/报告元数据
  - 模块：`skyedge-ui`、`app`（`ACCESS_FINE_LOCATION`）

- [ ] **P2-GPS-02** GPS 与地图视口联动（无 GeoTIFF 时定位到当前位置）
  - 模块：`skyedge-ui/map`

### 2.4 报告格式扩展

- [ ] **P2-REPORT-01** CSV 导出（异常表格式）
  - 契约：`ReportFormat.csv`

- [ ] **P2-REPORT-02** PDF 报告（图文混排 + 统计摘要）
  - 契约：`ReportFormat.pdf`
  - 说明：可用 Android `PdfDocument` 或轻量库

### 2.5 地图与地理能力增强

- [ ] **P2-GEO-01** GeoTIFF 支持 LZW/Deflate 压缩
  - 模块：`skyedge-core/geo/GeoTiffReader`

- [ ] **P2-GEO-02** 建筑物 + 道路同图双图层（串行推理或双模型叠加 UI）
  - 模块：`skyedge-core`、`skyedge-ui`

- [ ] **P2-GEO-03** 地图放大查看：mask 区域 pinch-zoom 细节
  - 模块：`skyedge-ui/map`

### 2.6 场景二（灾情响应）— 端侧部分

- [ ] **P2-DISASTER-01** 灾情框选：人工框选 bbox → 灾情卡片（`AnomalyType.debris` 等）
  - 模块：同 P1-ANNOTATE

- [ ] **P2-DISASTER-02** 灾情 severity 人工标注 + 端侧规则辅助打分
  - 模块：`skyedge-core`、`skyedge-ui`
  - 说明：不依赖服务端严重程度算法

- [ ] **P2-DISASTER-03** 本地多人 GPS 记录列表（单次任务内多次提交定位点）
  - 模块：`imgrecord`、`skyedge-core`
  - 说明：端侧仅存本地；聚合范围用简单凸包/中心点估算

---

## Phase 3 — 协同与体验升级（可选）

以下项与「无远程服务器」原则可能冲突，实施前需产品/架构决策。

### 3.1 跨人员协同

- [ ] **[-] P3-SYNC-01** 多人定位聚合 → 灾情范围纠偏（跨设备）
  - 备选 A：离线文件包导出/导入（JSON + 照片，人工汇总）
  - 备选 B：独立协同服务（**超出本 App 核心范围**）

- [ ] **[-] P3-SYNC-02** 人员调度系统对接
  - 说明：配套服务端，不在 `skyedge-*` 模块内实现

### 3.2 体验与模型扩展

- [ ] **[-] P3-UX-01** 3D 旋转地球入口（替代或补充现有 2D 高德地图）
  - 说明：需求描述中的「3D 地球」；评估 Cesium/Mapbox GL / 自研成本

- [ ] **[-] P3-MODEL-01** 地物分类（树木、车辆等）
  - 说明：需新模型或 SAM + 文本提示，不在当前 building/road 范围

- [ ] **P3-MODEL-02** 变化检测专用模型接入
  - 模块：`skyedge-core`、算法交付

---

## 按架构分层汇总

| 层级 | 模块 | Phase 1 重点 TODO |
|------|------|-------------------|
| 用户交互层 | `skyedge-ui` | 框选、卡片、拍照、报告预览/分享、样例图入口 |
| 业务逻辑层 | `skyedge-core` | `submitAnomaly`、`exportReport`、mask 实例拆分 |
| 推理引擎层 | `skyedge-core/model` | 连通域后处理；Phase 2 MobileSAM |
| 数据存储层 | `imgrecord` | `Task`、`Anomaly`、拍照附件、报告作业 |

---

## 依赖与阻塞

| TODO | 阻塞项 |
|------|--------|
| P2-SAM-* | 算法侧 `08_mobile_sam` TorchScript 交付 |
| P2-COMPARE-03 / P3-MODEL-02 | 变化检测模型交付 |
| P2-GEO-01 | GeoTIFF 解压库选型 |
| P1-E2E-01 | Phase 1 全部子项 |
| P3-SYNC-* | 产品决策：纯端侧 vs 协同服务 |

---

## 契约同步 checklist

每完成一批功能，同步更新：

- [ ] `openapi/api.yaml` 中对应 schema / `x-inProcessApi` 的 Kotlin 签名
- [ ] `InspectionFacade` / `InspectionUiState`（`skyedge-core/api`）
- [ ] [`ARCHITECTURE.md`](ARCHITECTURE.md) 调用链与包结构说明
- [ ] [`README.md`](../README.md) 功能概览（仅当有用户可见变化时）

---

## 修订记录

| 日期 | 说明 |
|------|------|
| 2026-06-08 | 初版：由产品需求整理为分阶段 TODO |

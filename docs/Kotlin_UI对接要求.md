# Kotlin 端侧 UI 交付包要求

> 目标：收到的包**合进本仓库后就能用**——外观对齐 Web 原型，**检测 / 核查 / 报告 / 地图等真功能全部正常**。  
> **实现必须是 Kotlin + Jetpack Compose**。仓库里的 `前端/`（HTML）只作视觉/流程参考，**不能**当作端侧交付物。

---

## 可直接复制发给他

请按仓库 `docs/Kotlin_UI对接要求.md` 打一个**可合入交付包**给我：

1. **UI 必须用 Kotlin + Jetpack Compose** 改现有 Android 工程（`skyedge-ui` / `MainActivity`）。  
   **不要**交 HTML/CSS/JS Web 页、不要 WebView 套 `前端/`、不要只给浏览器 Demo。
2. 基于我给你的**当前分支 zip / commit**，只改 Compose 界面与导航（视觉对齐 `前端/` 原型）。
3. 合入后必须：能加载模型、导入影像、出检测结果、人工核查保存、导出报告；GeoTIFF / 灾害采集若原来有，也要还能用。
4. **禁止**假数据 Prototype 入口、禁止换掉 `InferenceViewModel` 真链路。
5. 包内要有：补丁或完整 diff、页面对照表、自测清单、合入说明（我怎么 apply / merge）。

我这边验收标准就一条：**解压/打补丁 → Android Studio Sync → Run 重装，功能与改 UI 前一样能跑，只是界面变了。**

---

## 交付包必须长什么样

请打成一个 zip（或 git patch 系列），建议结构：

```text
skyedge-ui-handoff-YYYYMMDD/
  README.md                 # 合入步骤 + 自测结果（必填）
  BASELINE.txt              # 基于哪个 commit / 分支打的包
  patch/
    0001-....patch          # 或单个 changes.diff
  # 若不便打 patch，也可给「相对基线改过的完整文件列表」+ diff
  MAPPING.md                # Web 页 ↔ 真功能 API 对照表
  TESTCHECKLIST.md          # 自测勾选结果
```

### `README.md`（包内）至少写清

1. **合入命令**（任选其一，写死可复制命令）  
   - `git am patch/*.patch`  
   - 或 `git apply patch/changes.diff`  
   - 或「用 Android Studio 打开后把下列文件覆盖到对应路径」+ 文件清单  
2. **合入后操作**：Sync → Run 重装（不要只 Sync）  
3. **依赖/配置**：是否需要 `AMAP_API_KEY` 等（没有新依赖最好）  
4. **已知限制**：哪条流程还没接真 API（没有则写「无」）

### 硬性技术约束

| 必须 | 禁止 |
|------|------|
| **Kotlin + Jetpack Compose** 改端侧 UI | HTML / CSS / JS、纯 Web 包、WebView 嵌 `前端/` |
| 继续用现有 `InferenceViewModel` / Facade | 用 mock 列表冒充任务、建筑、检测结果 |
| 保留 `MainActivity` 里导入 / 拍照 / 定位 launcher 真回调 | `setContent { SkyEdgePrototypeApp() }` 这类假入口顶替真 App |
| UI 主要改 `skyedge-ui/.../ui/**`（`.kt` 文件） | 为了 Demo 删掉模型加载、Room、地图 |
| 导航改动要可回退、说得清 | 针对过旧 commit 打补丁导致无法 apply |

### 页面必须接到真功能（对照表模板）

| Web 原型 | 合入后必须调用的真能力 |
|----------|------------------------|
| 场景选择 | 进入建筑 / 灾害任务流 |
| 建筑任务 | `createTask` / `setActiveTask` |
| 影像识别 | `importImage` / `infer`；GeoTIFF → 地图 |
| 建筑标注 | `anomalies` + `reviewAnomaly` / `updateAnomaly` / 拍照 |
| 建筑报告 | `exportReport` |
| 灾害范围 | `startDisasterTrack` / `captureCurrentLocation` / `finishDisasterTrack` 等 |

---

## 对接人怎么收包、怎么验收

1. 确认 `BASELINE.txt` 和你当前工程 commit **一致或可 rebase**  
2. 按包内合入说明 apply  
3. Android Studio **Run 重装**  
4. 按下面清单点一遍——**全部通过才算合格**

### 验收清单（合入后 10 分钟）

- [ ] 能进 App，不是纯假数据演示页  
- [ ] 建筑：建/选任务 → 导入图或样例 → **有检测结果 / mask**  
- [ ] 核查：能选建筑、保存标注（数据还在）  
- [ ] 报告：能生成/导出文件  
- [ ] （若启用地图）GeoTIFF 仍能上图  
- [ ] （若做了灾害线）采集定位点 / 闭合仍可用  
- [ ] 界面是 **Kotlin Compose**（Android 原生界面），明显对齐 `前端/` 原型风格  
- [ ] 不是浏览器打开的 HTML，也不是 WebView 套壳

**失败即打回**：交的是 Web；apply 不上；Run 崩溃；只能点 UI 没有检测结果；入口变成 Prototype 假壳。

---

## 导航先对齐再打包

确认一种（写进包内 README）：

- **A. 场景栈 + 返回**（更接近 Web）  
- **B. 底栏 Tab**（改动小，更稳）  

未确认就交包 → 容易合入后你觉得「结构不对」。

---

## 相关路径（本仓库）

- Web 原型：`前端/`  
- 端侧 UI：`skyedge-ui/src/main/java/com/example/skyedge/ui/`  
- 入口：`app/src/main/java/com/example/skyedge/MainActivity.kt`  
- ViewModel：`skyedge-ui/.../inspection/InferenceViewModel.kt`

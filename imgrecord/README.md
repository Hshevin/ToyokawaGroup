# ImageRecord — Android 后端数据表组件

Android App 后端开源部件，Kotlin 开发，目标系统 Android 9（API 28）及以上。

## 数据表

| 列 | 类型 | 说明 |
|---|---|---|
| `local_url` | String（主键） | 本地目录绝对路径，格式：`{prefix}/{uuid}/` |
| `img_url` | String | 图片来源 URL |
| `analyse_type` | Int | 分析类型，见 `AnalyseType` enum |
| `status` | Int | 0=pending, 1=done, 2=failed |
| `time` | Long | 时间戳，单位 ms |
| `summary_json` | String | 分析结果 JSON |
| `err_info` | String? | 失败原因，status=failed 时非空 |

## 工作流程（方案 B — 异步两阶段）

### 阶段 1：`insert` — 快速写入

```
insert(imgUrl, analyseType) → 生成 local_url → 写入 pending 行 → 立即返回 local_url
```

1. 按 `{localUrlPrefix}/{uuid}/` 生成唯一 `local_url`（同一 `img_url` 重复输入也生成不同路径）
2. 插入一行：`status=pending`，`time=当前 ms`，`summary_json=""`，`err_info=null`
3. 后台启动分析任务，不阻塞返回

### 阶段 2：后台 `img_analyse` — 异步更新

```
img_analyse(local_url, img_url, analyse_type) → 更新 status/time/summary_json/err_info
```

- `img_analyse` 由集成方通过 `ImageAnalyser` 接口注入实现
- 成功：写入 `status=done`、`time`、`summary_json`
- 失败：写入 `status=failed`、`err_info`（持久化失败记录）

## API

| 函数 | 说明 |
|---|---|
| `insert(imgUrl, analyseType)` | 快速插入，返回 `local_url` |
| `analyseAndUpdate(localUrl)` | 手动触发/重试分析 |
| `queryByLocalUrl(localUrl)` | 按主键查询 |
| `queryByStatus(status)` | 按状态查询 |
| `traverse()` | 遍历全部记录 |
| `delete(localUrl)` | 删除单条 |
| `deleteAll()` | 清空表 |
| `getLocalUrlPrefix()` | 获取当前 local_url 前缀 |
| `setLocalUrlPrefix(prefix)` | 设置 local_url 前缀，后续 `insert` 生效 |

## 集成示例

```kotlin
val repo = ImageRecordRepository(
    context = context,
    localUrlPrefix = context.filesDir.absolutePath + "/analysis",
    analyser = myAnalyser,
    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
)

val localUrl = repo.insert("https://example.com/img.jpg", AnalyseType.BUILDING)
// 立即拿到 localUrl；分析完成后 status 自动更新

// 运行时修改前缀（仅影响后续 insert）
repo.setLocalUrlPrefix(context.cacheDir.absolutePath + "/analysis")
```

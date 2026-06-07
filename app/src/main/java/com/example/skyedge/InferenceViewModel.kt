package com.example.skyedge

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.skyedge.domain.InspectionResult
import com.example.skyedge.integration.SkyEdgeImageAnalyser
import com.example.skyedge.model.ImagePreprocessor
import com.example.skyedge.model.InferenceEngine
import com.example.skyedge.model.PytorchInferenceEngine
import imgrecord.ImageRecordRepository
import imgrecord.model.AnalyseStatus
import imgrecord.model.AnalyseType
import imgrecord.model.ImageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.UUID

data class InferenceUiState(
    val statusMessage: String = "正在加载模型…",
    val isLoadingModel: Boolean = true,
    val isInferring: Boolean = false,
    val isModelReady: Boolean = false,
    val lastMaskPath: String? = null,
    val selectedModelKey: String = ModelChoice.BUILDING.key,
    val recentRecords: List<InspectionRecordItem> = emptyList(),
)

data class InspectionRecordItem(
    val localUrl: String,
    val analyseType: String,
    val status: String,
    val detail: String,
)

data class ModelChoice(
    val key: String,
    val label: String,
    val specAsset: String,
) {
    companion object {
        val BUILDING = ModelChoice(
            key = "building",
            label = "Building",
            specAsset = "models/building_unet_efficientnetb0_v1/model_spec.json",
        )
        val ROAD = ModelChoice(
            key = "road",
            label = "Road",
            specAsset = "models/road_unet_efficientnetb0_v1/model_spec.json",
        )

        val ALL = listOf(BUILDING, ROAD)

        fun fromKey(key: String): ModelChoice = ALL.firstOrNull { it.key == key } ?: BUILDING
    }
}

class InferenceViewModel(application: Application) : AndroidViewModel(application) {

    private val engine: InferenceEngine = PytorchInferenceEngine(application)
    private val imageRecordRepository = ImageRecordRepository(
        context = application,
        localUrlPrefix = application.filesDir.absolutePath + "/analysis",
        analyser = SkyEdgeImageAnalyser(application, engine),
        scope = viewModelScope,
    )
    var uiState by mutableStateOf(InferenceUiState())
        private set

    val modelChoices: List<ModelChoice> = ModelChoice.ALL

    init {
        loadModel()
        refreshHistory()
    }

    fun loadModel(modelKey: String = uiState.selectedModelKey) {
        val choice = ModelChoice.fromKey(modelKey)
        viewModelScope.launch {
            uiState = uiState.copy(
                isLoadingModel = true,
                isModelReady = false,
                statusMessage = "正在加载模型（${choice.label}）…",
                selectedModelKey = choice.key,
            )
            val result = withContext(Dispatchers.Default) { engine.load(choice.specAsset) }
            uiState = result.fold(
                onSuccess = {
                    InferenceUiState(
                        statusMessage = "模型加载成功：${engine.loadedModelVersion ?: "unknown"}\n请选择图片",
                        isLoadingModel = false,
                        isModelReady = true,
                        selectedModelKey = choice.key,
                    )
                },
                onFailure = {
                    InferenceUiState(
                        statusMessage = "模型加载失败: ${it.message}",
                        isLoadingModel = false,
                        isModelReady = false,
                        selectedModelKey = choice.key,
                    )
                },
            )
        }
    }

    fun switchModel(modelKey: String) {
        if (uiState.isInferring || uiState.isLoadingModel) return
        if (uiState.selectedModelKey == modelKey && uiState.isModelReady) return
        uiState = uiState.copy(lastMaskPath = null, statusMessage = "正在切换模型…")
        loadModel(modelKey)
    }

    fun updateStatus(message: String) {
        uiState = uiState.copy(statusMessage = message)
    }

    fun infer(uri: Uri) {
        if (uiState.isInferring) return
        viewModelScope.launch {
            uiState = uiState.copy(
                isInferring = true,
                statusMessage = "推理中…",
                lastMaskPath = null,
            )
            val analyseType = when (uiState.selectedModelKey) {
                ModelChoice.ROAD.key -> AnalyseType.ROAD
                else -> AnalyseType.BUILDING
            }
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val localUrl = imageRecordRepository.insert(uri.toString(), analyseType)
                    val finalRecord = awaitRecord(localUrl)
                        ?: error("数据库记录丢失")
                    localUrl to finalRecord
                }
            }
            val recent = withContext(Dispatchers.IO) { loadRecentRecords() }
            uiState = outcome.fold(
                onSuccess = { (localUrl, finalRecord) ->
                    when (finalRecord.status) {
                        AnalyseStatus.DONE -> {
                            val maskPath = SkyEdgeImageAnalyser.maskPathFromSummary(finalRecord.summaryJson)
                            uiState.copy(
                                isInferring = false,
                                lastMaskPath = maskPath,
                                recentRecords = recent,
                                statusMessage = formatRecordStatus(localUrl, finalRecord.summaryJson),
                            )
                        }
                        AnalyseStatus.FAILED -> uiState.copy(
                            isInferring = false,
                            recentRecords = recent,
                            statusMessage = "检测失败: ${finalRecord.errInfo ?: "unknown"}",
                        )
                        AnalyseStatus.PENDING -> uiState.copy(
                            isInferring = false,
                            recentRecords = recent,
                            statusMessage = "检测超时: 记录仍为 pending\nlocal_url: $localUrl",
                        )
                    }
                },
                onFailure = { error ->
                    uiState.copy(
                        isInferring = false,
                        recentRecords = recent,
                        statusMessage = "检测失败: ${error.message}",
                    )
                },
            )
        }
    }

    fun refreshHistory() {
        viewModelScope.launch {
            val recent = withContext(Dispatchers.IO) { loadRecentRecords() }
            uiState = uiState.copy(recentRecords = recent)
        }
    }

    private suspend fun awaitRecord(localUrl: String): ImageRecord? =
        withTimeoutOrNull(INFERENCE_WAIT_MS) {
            var record = imageRecordRepository.queryByLocalUrl(localUrl)
            while (record?.status == AnalyseStatus.PENDING) {
                delay(POLL_INTERVAL_MS)
                record = imageRecordRepository.queryByLocalUrl(localUrl)
            }
            record
        }

    private suspend fun loadRecentRecords(): List<InspectionRecordItem> =
        imageRecordRepository.traverse()
            .sortedByDescending { it.time }
            .take(RECENT_RECORD_LIMIT)
            .map { it.toItem() }

    private fun ImageRecord.toItem(): InspectionRecordItem {
        val ratioLine = runCatching {
            JSONObject(summaryJson).optDouble("defect_area_ratio", -1.0)
        }.getOrDefault(-1.0).takeIf { it >= 0 }?.let { "占比 ${"%.1f".format(it * 100)}%" } ?: ""
        return InspectionRecordItem(
            localUrl = localUrl,
            analyseType = analyseType.name,
            status = status.name,
            detail = ratioLine,
        )
    }

    private fun formatRecordStatus(localUrl: String, summaryJson: String): String = buildString {
        append("检测完成\n")
        append("local_url: $localUrl\n")
        runCatching {
            val json = JSONObject(summaryJson)
            json.optString("model_version").takeIf { it.isNotBlank() }?.let {
                append("模型: $it\n")
            }
            json.optString("mask_path").takeIf { it.isNotBlank() }?.let {
                append("mask: $it\n")
            }
            if (json.has("defect_area_ratio")) {
                val pct = json.getDouble("defect_area_ratio") * 100.0
                append(
                    if (pct <= 0.01) {
                        "检测结果: 未识别到目标区域（可换道路更明显的图片）"
                    } else {
                        "目标区域占比: ${"%.2f".format(pct)}%"
                    },
                )
                append('\n')
            }
            json.optLong("inference_ms").takeIf { it > 0 }?.let {
                append("耗时: $it ms")
            }
        }.onFailure {
            append(summaryJson)
        }
    }

    fun benchmarkCurrentImage(uri: Uri, runs: Int = 10) {
        if (!engine.isReady || uiState.isInferring || runs <= 0) return
        viewModelScope.launch {
            uiState = uiState.copy(
                isInferring = true,
                statusMessage = "基准测试中…（$runs 次）",
                lastMaskPath = null,
            )
            val localIdPrefix = UUID.randomUUID().toString()
            val outcome = withContext(Dispatchers.Default) {
                val bitmap = ImagePreprocessor.loadOrientedBitmap(getApplication(), uri)
                    ?: return@withContext Result.failure<BenchmarkOutcome>(
                        IllegalStateException("无法读取图片"),
                    )
                runCatching {
                    val msList = mutableListOf<Long>()
                    var lastResult: InspectionResult? = null
                    repeat(runs) { idx ->
                        val result = engine.infer(bitmap, "${localIdPrefix}_$idx").getOrThrow()
                        msList += result.inferenceMsOrNull
                            ?: throw IllegalStateException("无法读取推理耗时")
                        lastResult = result
                    }
                    bitmap.recycle()
                    val sorted = msList.sorted()
                    BenchmarkOutcome(
                        avgMs = msList.average(),
                        p90Ms = sorted[(sorted.size * 0.9).toInt().coerceAtLeast(1) - 1],
                        timesMs = msList,
                        lastResult = lastResult ?: error("缺少推理结果"),
                    )
                }
            }
            uiState = outcome.fold(
                onSuccess = { done ->
                    val summary = buildString {
                        append("基准测试完成（$runs 次）\n")
                        append("模型: ${engine.loadedModelVersion ?: "unknown"}\n")
                        append("avg: ${"%.1f".format(done.avgMs)} ms\n")
                        append("p90: ${done.p90Ms} ms\n")
                        append("times: ${done.timesMs.joinToString(",")}")
                    }
                    uiState.copy(
                        isInferring = false,
                        lastMaskPath = (done.lastResult as? InspectionResult.Segmentation)?.maskPath,
                        statusMessage = "${done.lastResult.displayText()}\n$summary",
                    )
                },
                onFailure = {
                    uiState.copy(
                        isInferring = false,
                        statusMessage = "基准测试失败: ${it.message}",
                    )
                },
            )
        }
    }

    override fun onCleared() {
        engine.close()
        super.onCleared()
    }

    private data class BenchmarkOutcome(
        val avgMs: Double,
        val p90Ms: Long,
        val timesMs: List<Long>,
        val lastResult: InspectionResult,
    )

    companion object {
        private const val POLL_INTERVAL_MS = 100L
        private const val INFERENCE_WAIT_MS = 180_000L
        private const val RECENT_RECORD_LIMIT = 5
    }
}

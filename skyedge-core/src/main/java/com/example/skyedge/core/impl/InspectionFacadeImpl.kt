package com.example.skyedge.core.impl

import android.content.Context
import android.net.Uri
import com.example.skyedge.core.api.InspectionFacade
import com.example.skyedge.core.api.InspectionRecordItem
import com.example.skyedge.core.api.InspectionUiState
import com.example.skyedge.core.api.ModelChoice
import com.example.skyedge.core.domain.InspectionResult
import com.example.skyedge.core.integration.SkyEdgeImageAnalyser
import com.example.skyedge.core.model.ImagePreprocessor
import com.example.skyedge.core.model.InferenceEngine
import com.example.skyedge.core.model.PytorchInferenceEngine
import imgrecord.ImageRecordRepository
import imgrecord.model.AnalyseStatus
import imgrecord.model.AnalyseType
import imgrecord.model.ImageRecord
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class InspectionFacadeImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    private val engine: InferenceEngine = PytorchInferenceEngine(context),
) : InspectionFacade {

    private val imageRecordRepository = ImageRecordRepository(
        context = context,
        localUrlPrefix = context.filesDir.absolutePath + "/analysis",
        analyser = SkyEdgeImageAnalyser(context, engine),
        scope = scope,
    )

    private val _state = MutableStateFlow(InspectionUiState())
    override val state: StateFlow<InspectionUiState> = _state.asStateFlow()

    override val modelChoices: List<ModelChoice> = ModelChoice.ALL

    init {
        loadModel()
        refreshHistory()
    }

    override fun loadModel(modelKey: String) {
        val choice = ModelChoice.fromKey(modelKey)
        scope.launch {
            _state.update {
                it.copy(
                    isLoadingModel = true,
                    isModelReady = false,
                    statusMessage = "正在加载模型（${choice.label}）…",
                    selectedModelKey = choice.key,
                )
            }
            val result = withContext(Dispatchers.Default) { engine.load(choice.specAsset) }
            _state.update { current ->
                result.fold(
                    onSuccess = {
                        InspectionUiState(
                            statusMessage = "模型加载成功：${engine.loadedModelVersion ?: "unknown"}\n请选择图片",
                            isLoadingModel = false,
                            isModelReady = true,
                            selectedModelKey = choice.key,
                            recentRecords = current.recentRecords,
                        )
                    },
                    onFailure = {
                        InspectionUiState(
                            statusMessage = "模型加载失败: ${it.message}",
                            isLoadingModel = false,
                            isModelReady = false,
                            selectedModelKey = choice.key,
                            recentRecords = current.recentRecords,
                        )
                    },
                )
            }
        }
    }

    override fun switchModel(modelKey: String) {
        val current = _state.value
        if (current.isInferring || current.isLoadingModel) return
        if (current.selectedModelKey == modelKey && current.isModelReady) return
        _state.update { it.copy(lastMaskPath = null, statusMessage = "正在切换模型…") }
        loadModel(modelKey)
    }

    override fun updateStatus(message: String) {
        _state.update { it.copy(statusMessage = message) }
    }

    override suspend fun infer(uri: Uri) {
        if (_state.value.isInferring) return
        _state.update {
            it.copy(
                isInferring = true,
                statusMessage = "推理中…",
                lastMaskPath = null,
            )
        }
        val analyseType = when (_state.value.selectedModelKey) {
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
        _state.update { current ->
            outcome.fold(
                onSuccess = { (localUrl, finalRecord) ->
                    when (finalRecord.status) {
                        AnalyseStatus.DONE -> current.copy(
                            isInferring = false,
                            lastMaskPath = SkyEdgeImageAnalyser.maskPathFromSummary(finalRecord.summaryJson),
                            recentRecords = recent,
                            statusMessage = formatRecordStatus(localUrl, finalRecord.summaryJson),
                        )
                        AnalyseStatus.FAILED -> current.copy(
                            isInferring = false,
                            recentRecords = recent,
                            statusMessage = "检测失败: ${finalRecord.errInfo ?: "unknown"}",
                        )
                        AnalyseStatus.PENDING -> current.copy(
                            isInferring = false,
                            recentRecords = recent,
                            statusMessage = "检测超时: 记录仍为 pending\nlocal_url: $localUrl",
                        )
                    }
                },
                onFailure = { error ->
                    current.copy(
                        isInferring = false,
                        recentRecords = recent,
                        statusMessage = "检测失败: ${error.message}",
                    )
                },
            )
        }
    }

    override fun refreshHistory() {
        scope.launch {
            val recent = withContext(Dispatchers.IO) { loadRecentRecords() }
            _state.update { it.copy(recentRecords = recent) }
        }
    }

    override suspend fun benchmark(uri: Uri, runs: Int) {
        if (!engine.isReady || _state.value.isInferring || runs <= 0) return
        _state.update {
            it.copy(
                isInferring = true,
                statusMessage = "基准测试中…（$runs 次）",
                lastMaskPath = null,
            )
        }
        val localIdPrefix = UUID.randomUUID().toString()
        val outcome = withContext(Dispatchers.Default) {
            val bitmap = ImagePreprocessor.loadOrientedBitmap(context, uri)
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
        _state.update { current ->
            outcome.fold(
                onSuccess = { done ->
                    val summary = buildString {
                        append("基准测试完成（$runs 次）\n")
                        append("模型: ${engine.loadedModelVersion ?: "unknown"}\n")
                        append("avg: ${"%.1f".format(done.avgMs)} ms\n")
                        append("p90: ${done.p90Ms} ms\n")
                        append("times: ${done.timesMs.joinToString(",")}")
                    }
                    current.copy(
                        isInferring = false,
                        lastMaskPath = (done.lastResult as? InspectionResult.Segmentation)?.maskPath,
                        statusMessage = "${done.lastResult.displayText()}\n$summary",
                    )
                },
                onFailure = {
                    current.copy(
                        isInferring = false,
                        statusMessage = "基准测试失败: ${it.message}",
                    )
                },
            )
        }
    }

    override fun close() {
        engine.close()
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

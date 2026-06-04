package com.example.skyedge

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.skyedge.domain.InspectionResult
import com.example.skyedge.model.ImagePreprocessor
import com.example.skyedge.model.InferenceEngine
import com.example.skyedge.model.PytorchInferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class InferenceUiState(
    val statusMessage: String = "正在加载模型…",
    val isLoadingModel: Boolean = true,
    val isInferring: Boolean = false,
    val isModelReady: Boolean = false,
    val lastMaskPath: String? = null,
    val selectedModelKey: String = ModelChoice.BUILDING.key,
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
    var uiState by mutableStateOf(InferenceUiState())
        private set

    val modelChoices: List<ModelChoice> = ModelChoice.ALL

    init {
        loadModel()
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
        if (!engine.isReady || uiState.isInferring) return
        viewModelScope.launch {
            uiState = uiState.copy(
                isInferring = true,
                statusMessage = "推理中…",
                lastMaskPath = null,
            )
            val localId = UUID.randomUUID().toString()
            val outcome = withContext(Dispatchers.Default) {
                val bitmap = ImagePreprocessor.loadOrientedBitmap(getApplication(), uri)
                    ?: return@withContext Result.failure<InspectionResult>(
                        IllegalStateException("无法读取图片"),
                    )
                val result = engine.infer(bitmap, localId)
                bitmap.recycle()
                result
            }
            uiState = outcome.fold(
                onSuccess = { result ->
                    uiState.copy(
                        isInferring = false,
                        lastMaskPath = (result as? InspectionResult.Segmentation)?.maskPath,
                        statusMessage = "${result.displayText()}\nlocalId=$localId",
                    )
                },
                onFailure = {
                    uiState.copy(
                        isInferring = false,
                        statusMessage = "推理出错: ${it.message}",
                    )
                },
            )
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
}

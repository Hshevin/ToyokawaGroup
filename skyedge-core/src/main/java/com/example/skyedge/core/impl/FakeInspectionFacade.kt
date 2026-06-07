package com.example.skyedge.core.impl

import android.net.Uri
import com.example.skyedge.core.api.InspectionFacade
import com.example.skyedge.core.api.InspectionRecordItem
import com.example.skyedge.core.api.InspectionUiState
import com.example.skyedge.core.api.ModelChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 无 PyTorch 依赖的 Facade 桩，供 UI Preview 与单元测试使用。
 */
class FakeInspectionFacade(
    initialState: InspectionUiState = InspectionUiState(
        statusMessage = "模型加载成功：fake_model.pt\n请选择图片",
        isLoadingModel = false,
        isModelReady = true,
        recentRecords = listOf(
            InspectionRecordItem(
                localUrl = "/data/analysis/demo/",
                analyseType = "BUILDING",
                status = "DONE",
                detail = "占比 12.3%",
            ),
        ),
    ),
) : InspectionFacade {

    private val _state = MutableStateFlow(initialState)
    override val state: StateFlow<InspectionUiState> = _state.asStateFlow()
    override val modelChoices: List<ModelChoice> = ModelChoice.ALL

    override fun loadModel(modelKey: String) = Unit

    override fun switchModel(modelKey: String) {
        _state.value = _state.value.copy(
            selectedModelKey = modelKey,
            statusMessage = "已切换至 ${ModelChoice.fromKey(modelKey).label}",
        )
    }

    override fun updateStatus(message: String) {
        _state.value = _state.value.copy(statusMessage = message)
    }

    override suspend fun infer(uri: Uri) {
        _state.value = _state.value.copy(
            isInferring = false,
            statusMessage = "检测完成（Fake）\nuri: $uri",
        )
    }

    override suspend fun benchmark(uri: Uri, runs: Int) {
        _state.value = _state.value.copy(
            isInferring = false,
            statusMessage = "基准测试完成（Fake，$runs 次）",
        )
    }

    override fun refreshHistory() = Unit

    override fun close() = Unit
}

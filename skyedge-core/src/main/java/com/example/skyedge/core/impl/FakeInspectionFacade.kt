package com.example.skyedge.core.impl

import android.net.Uri
import com.example.skyedge.core.api.AnomalyUpdateRequest
import com.example.skyedge.core.api.AnomalyUiModel
import com.example.skyedge.core.api.CreateTaskRequest
import com.example.skyedge.core.api.DisasterPointUiModel
import com.example.skyedge.core.api.GeoBoundsDto
import com.example.skyedge.core.api.GeoLatLngDto
import com.example.skyedge.core.api.InspectionFacade
import com.example.skyedge.core.api.InspectionRecordItem
import com.example.skyedge.core.api.InspectionUiState
import com.example.skyedge.core.api.MapSessionUiModel
import com.example.skyedge.core.api.ModelChoice
import com.example.skyedge.core.api.ReportExportResult
import com.example.skyedge.core.api.ReportFormat
import com.example.skyedge.core.api.ReviewAnomalyRequest
import com.example.skyedge.core.api.SubmitAnomalyRequest
import com.example.skyedge.core.api.TaskUiModel
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
                sourceUri = "file:///data/analysis/demo/source.png",
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

    override suspend fun encodeInteractiveImage(uri: Uri) {
        _state.value = _state.value.copy(
            isInferring = false,
            interactiveImageReady = true,
            statusMessage = "MobileSAM 编码完成（Fake）",
        )
    }

    override suspend fun inferInteractivePoint(x: Float, y: Float, imageWidth: Int, imageHeight: Int) {
        _state.value = _state.value.copy(
            isInferring = false,
            statusMessage = "MobileSAM 点击分割完成（Fake）",
        )
    }

    override suspend fun runMobileSamDemo(demoName: String) {
        _state.value = _state.value.copy(
            isInferring = false,
            interactiveImageReady = true,
            statusMessage = "MobileSAM 演示完成（Fake）: $demoName",
        )
    }

    override suspend fun selectCorrectionRoi(
        uri: Uri,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        imageWidth: Int,
        imageHeight: Int,
    ) {
        _state.value = _state.value.copy(
            statusMessage = "框选 ROI 已更新（Fake）",
            interactiveRoiActive = true,
        )
    }

    override suspend fun loadGeoTiff(uri: Uri) {
        _state.value = _state.value.copy(
            statusMessage = "GeoTIFF 已加载（Fake）\nuri: $uri",
            mapSession = demoMapSession(maskOverlayPath = null),
        )
    }

    override suspend fun inferMapSession() {
        _state.value = _state.value.copy(
            isInferring = false,
            statusMessage = "地图检测完成（Fake）",
            mapSession = (_state.value.mapSession ?: demoMapSession()).copy(
                maskOverlayPath = "/data/analysis/demo/mask_overlay.png",
            ),
        )
    }

    override suspend fun createTask(request: CreateTaskRequest): TaskUiModel {
        val now = System.currentTimeMillis()
        val task = TaskUiModel(
            id = "fake-task",
            name = request.name.ifBlank { request.sceneType.label },
            sceneType = request.sceneType,
            areaName = request.areaName,
            status = com.example.skyedge.core.api.TaskStatusUi.MANUAL_REVIEW,
            priority = request.priority,
            operator = request.operator,
            createdAt = now,
            updatedAt = now,
        )
        _state.value = _state.value.copy(tasks = listOf(task), activeTask = task)
        return task
    }

    override suspend fun listTasks(): List<TaskUiModel> = _state.value.tasks

    override suspend fun getTask(taskId: String): TaskUiModel? =
        _state.value.tasks.firstOrNull { it.id == taskId }

    override fun setActiveTask(taskId: String) {
        _state.value.tasks.firstOrNull { it.id == taskId }?.let {
            _state.value = _state.value.copy(activeTask = it)
        }
    }

    override suspend fun listAnomalies(taskId: String): List<AnomalyUiModel> =
        _state.value.anomalies.filter { it.taskId == taskId }

    override suspend fun submitAnomaly(request: SubmitAnomalyRequest): String {
        _state.value = _state.value.copy(statusMessage = "异常已提交（Fake）")
        return "fake-anomaly"
    }

    override suspend fun reviewAnomaly(id: String, request: ReviewAnomalyRequest) {
        _state.value = _state.value.copy(statusMessage = "异常已复核（Fake）")
    }

    override suspend fun updateAnomaly(id: String, fields: AnomalyUpdateRequest) {
        _state.value = _state.value.copy(statusMessage = "异常已更新（Fake）")
    }

    override suspend fun attachPhoto(anomalyId: String, uri: Uri) {
        _state.value = _state.value.copy(statusMessage = "照片已绑定（Fake）: $uri")
    }

    override suspend fun exportReport(taskId: String, formats: Set<ReportFormat>): ReportExportResult {
        val result = ReportExportResult(
            taskId = taskId,
            exportedAt = System.currentTimeMillis(),
            files = formats.associateWith { "/data/analysis/demo/report.${it.value}" },
        )
        _state.value = _state.value.copy(lastReport = result, statusMessage = "报告已导出（Fake）")
        return result
    }

    override fun startDisasterTrack() {
        _state.value = _state.value.copy(disasterTrack = _state.value.disasterTrack.copy(isCollecting = true))
    }

    override fun addDisasterPoint(lat: Double, lng: Double) {
        _state.value = _state.value.copy(
            disasterTrack = _state.value.disasterTrack.copy(
                points = _state.value.disasterTrack.points + DisasterPointUiModel(lat, lng, System.currentTimeMillis()),
            ),
        )
    }

    override suspend fun captureCurrentLocation() {
        addDisasterPoint(35.681, 139.767)
    }

    override fun finishDisasterTrack() {
        _state.value = _state.value.copy(disasterTrack = _state.value.disasterTrack.copy(isCollecting = false, isClosed = true))
    }

    override fun resetDisasterTrack() {
        _state.value = _state.value.copy(disasterTrack = com.example.skyedge.core.api.DisasterTrackUiModel())
    }

    override fun setCompareImages(historicalUri: Uri?, currentUri: Uri?) {
        _state.value = _state.value.copy(
            compareSession = _state.value.compareSession.copy(
                historicalImageUri = historicalUri?.toString() ?: _state.value.compareSession.historicalImageUri,
                currentImageUri = currentUri?.toString() ?: _state.value.compareSession.currentImageUri,
            ),
        )
    }

    override fun setCompareSlider(value: Float) {
        _state.value = _state.value.copy(compareSession = _state.value.compareSession.copy(slider = value.coerceIn(0f, 1f)))
    }

    override fun refineMaskAt(x: Float, y: Float) {
        _state.value = _state.value.copy(statusMessage = "MobileSAM 模型待算法侧交付，已记录点选位置：$x,$y")
    }

    override fun setMapLayerVisibility(showOrtho: Boolean, showMask: Boolean) {
        _state.value = _state.value.copy(
            mapSession = _state.value.mapSession?.copy(
                showOrtho = showOrtho,
                showMask = showMask,
            ),
        )
    }

    override fun setMaskAlpha(alpha: Float) {
        _state.value = _state.value.copy(
            mapSession = _state.value.mapSession?.copy(maskAlpha = alpha.coerceIn(0f, 1f)),
        )
    }

    override fun clearMapSession() {
        _state.value = _state.value.copy(mapSession = null, statusMessage = "地图会话已清除（Fake）")
    }

    override fun selectAnomaly(id: String?) {
        _state.value = _state.value.copy(selectedAnomalyId = id)
    }

    override suspend fun benchmark(uri: Uri, runs: Int) {
        _state.value = _state.value.copy(
            isInferring = false,
            statusMessage = "基准测试完成（Fake，$runs 次）",
        )
    }

    override fun refreshHistory() = Unit

    override fun close() = Unit

    private fun demoMapSession(maskOverlayPath: String? = "/data/analysis/demo/mask_overlay.png"): MapSessionUiModel =
        MapSessionUiModel(
            sessionId = "fake-map-session",
            boundsGcj02 = GeoBoundsDto(
                sw = GeoLatLngDto(lat = 35.676, lng = 139.760),
                ne = GeoLatLngDto(lat = 35.686, lng = 139.775),
            ),
            orthoPreviewPath = "/data/analysis/demo/preview.png",
            maskOverlayPath = maskOverlayPath,
        )
}

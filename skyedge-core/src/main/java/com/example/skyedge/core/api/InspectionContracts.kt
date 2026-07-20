package com.example.skyedge.core.api

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

data class InspectionUiState(
    val statusMessage: String = "正在加载模型…",
    val isLoadingModel: Boolean = true,
    val isInferring: Boolean = false,
    val isModelReady: Boolean = false,
    val lastMaskPath: String? = null,
    /** Building 首次检测的 mask，局部修正始终与之合并，避免被 SAM 小片覆盖。 */
    val buildingMaskPath: String? = null,
    /** 每次 mask 文件更新 +1，用于强制刷新右侧 overlay（同路径覆写时 path 不变）。 */
    val maskUpdateSeq: Long = 0L,
    val selectedModelKey: String = ModelChoice.BUILDING.key,
    val interactiveImageReady: Boolean = false,
    val interactiveImageWidth: Int? = null,
    val interactiveImageHeight: Int? = null,
    val interactiveRoiActive: Boolean = false,
    val interactivePoints: List<InteractivePoint> = emptyList(),
    val recentRecords: List<InspectionRecordItem> = emptyList(),
    val mapSession: MapSessionUiModel? = null,
    val tasks: List<TaskUiModel> = emptyList(),
    val activeTask: TaskUiModel? = null,
    val anomalies: List<AnomalyUiModel> = emptyList(),
    val selectedAnomalyId: String? = null,
    val reportDraft: ReportDraftUiModel? = null,
    val lastReport: ReportExportResult? = null,
    val disasterTrack: DisasterTrackUiModel = DisasterTrackUiModel(),
    val compareSession: CompareSessionUiModel = CompareSessionUiModel(),
)

data class InteractivePoint(
    val x: Float,
    val y: Float,
    val label: Int = 1,
)

data class GeoLatLngDto(
    val lat: Double,
    val lng: Double,
)

data class GeoBoundsDto(
    val sw: GeoLatLngDto,
    val ne: GeoLatLngDto,
)

data class MapSessionUiModel(
    val sessionId: String,
    val boundsGcj02: GeoBoundsDto,
    val orthoPreviewPath: String,
    val maskOverlayPath: String? = null,
    val showOrtho: Boolean = true,
    val showMask: Boolean = true,
    val maskAlpha: Float = 0.42f,
    val isLoadingGeo: Boolean = false,
    val geoError: String? = null,
)

data class InspectionRecordItem(
    val localUrl: String,
    val sourceUri: String,
    val analyseType: String,
    val status: String,
    val detail: String,
)

enum class SceneTypeUi(val value: String, val label: String) {
    BUILDING("building", "建筑核查"),
    DISASTER("disaster", "灾害应急"),
}

enum class TaskStatusUi(val value: String, val label: String) {
    DRAFT("draft", "草稿"),
    MANUAL_REVIEW("manual_review", "人工核查"),
    READY_TO_EXPORT("ready_to_export", "待导出"),
    EXPORTED("exported", "已导出"),
    PENDING_REPORT("pending_report", "待上报"),
}

enum class TaskPriorityUi(val value: String, val label: String) {
    LOW("low", "低"),
    NORMAL("normal", "普通"),
    HIGH("high", "高"),
}

data class TaskUiModel(
    val id: String,
    val name: String,
    val sceneType: SceneTypeUi,
    val areaName: String,
    val status: TaskStatusUi,
    val priority: TaskPriorityUi,
    val operator: String,
    val createdAt: Long,
    val updatedAt: Long,
    val imageCount: Int = 0,
    val anomalyCount: Int = 0,
)

data class CreateTaskRequest(
    val name: String,
    val sceneType: SceneTypeUi = SceneTypeUi.BUILDING,
    val areaName: String = "",
    val priority: TaskPriorityUi = TaskPriorityUi.NORMAL,
    val operator: String = "",
)

enum class AnomalyTypeUi(val value: String, val label: String) {
    NEW_BUILDING("new_building", "新建建筑"),
    SUSPECTED_ILLEGAL("suspected_illegal", "疑似违建"),
    TEMPORARY_STRUCTURE("temporary_structure", "临时搭建"),
    DAMAGED_COLLAPSED("damaged_collapsed", "损毁/倒塌"),
    DEBRIS("debris", "堆积物"),
    LANDSLIDE("landslide", "滑坡"),
    OTHER("other", "其他"),
}

enum class ReviewStatusUi(val value: String, val label: String) {
    PENDING("pending", "未标注"),
    CONFIRMED("confirmed", "已标注"),
    VERIFIED("verified", "已核验"),
    REJECTED("rejected", "核验有误"),
}

enum class SeverityUi(val value: String, val label: String) {
    LOW("low", "低"),
    MEDIUM("medium", "中"),
    HIGH("high", "高"),
    CRITICAL("critical", "紧急");

    companion object {
        fun fromValue(value: String?): SeverityUi =
            entries.firstOrNull { it.value == value } ?: MEDIUM
    }
}

data class BoundingBoxDto(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class AnomalyUiModel(
    val id: String,
    val taskId: String,
    val imageLocalUrl: String?,
    val bbox: BoundingBoxDto,
    val anomalyType: AnomalyTypeUi,
    val reviewStatus: ReviewStatusUi,
    val buildingCode: String,
    val location: String,
    val source: String,
    val comment: String,
    val severity: String,
    val thumbnailPath: String,
    val photoPaths: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
)

data class SubmitAnomalyRequest(
    val taskId: String,
    val imageLocalUrl: String?,
    val bbox: BoundingBoxDto,
    val anomalyType: AnomalyTypeUi = AnomalyTypeUi.OTHER,
    val buildingCode: String = "",
    val location: String = "",
    val comment: String = "",
    val severity: String = "",
)

data class ReviewAnomalyRequest(
    val status: ReviewStatusUi,
    val anomalyType: AnomalyTypeUi? = null,
    val comment: String? = null,
)

data class AnomalyUpdateRequest(
    val anomalyType: AnomalyTypeUi? = null,
    val reviewStatus: ReviewStatusUi? = null,
    val buildingCode: String? = null,
    val location: String? = null,
    val comment: String? = null,
    val severity: String? = null,
    val bbox: BoundingBoxDto? = null,
)

enum class ReportFormat(val value: String, val label: String) {
    IMAGE("image", "图片"),
    JSON("json", "JSON"),
    CSV("csv", "CSV"),
    PDF("pdf", "PDF"),
    GEOJSON("geojson", "GeoJSON"),
}

data class ReportDraftUiModel(
    val taskId: String,
    val objectCount: Int,
    val confirmedCount: Int,
    val rejectedCount: Int,
    val typeCounts: Map<AnomalyTypeUi, Int>,
    val anomalies: List<AnomalyUiModel> = emptyList(),
)

data class ReportExportResult(
    val taskId: String,
    val exportedAt: Long,
    val files: Map<ReportFormat, String>,
)

data class DisasterPointUiModel(
    val lat: Double,
    val lng: Double,
    val time: Long,
)

data class DisasterTrackUiModel(
    val isCollecting: Boolean = false,
    val isClosed: Boolean = false,
    val points: List<DisasterPointUiModel> = emptyList(),
    val riskLevel: String = "高",
    val disasterType: String = "滑坡",
    val affectedObjects: String = "道路、房屋",
    val description: String = "",
)

data class CompareSessionUiModel(
    val historicalImageUri: String? = null,
    val currentImageUri: String? = null,
    val slider: Float = 0.5f,
    val samStatus: String = "点选修正请在影像页操作（检测完成后可用）",
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
        val MOBILE_SAM = ModelChoice(
            key = "mobile_sam",
            label = "MobileSAM",
            specAsset = "models/mobile_sam_interactive_v1/model_spec.json",
        )

        /** 检测页可见模型；MobileSAM 作为 Building 检测后的修正引擎，不在 UI 切换。 */
        val ALL = listOf(BUILDING, ROAD)

        fun fromKey(key: String): ModelChoice = ALL.firstOrNull { it.key == key } ?: BUILDING
    }
}

interface InspectionFacade {
    val state: StateFlow<InspectionUiState>
    val modelChoices: List<ModelChoice>

    fun loadModel(modelKey: String = ModelChoice.BUILDING.key)
    fun switchModel(modelKey: String)
    fun updateStatus(message: String)
    suspend fun infer(uri: Uri)
    suspend fun encodeInteractiveImage(uri: Uri)
    suspend fun inferInteractivePoint(x: Float, y: Float, imageWidth: Int, imageHeight: Int)
    suspend fun selectCorrectionRoi(
        uri: Uri,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        imageWidth: Int,
        imageHeight: Int,
    )
    suspend fun runMobileSamDemo(demoName: String = "building_demo")
    suspend fun loadGeoTiff(uri: Uri)
    suspend fun inferMapSession()
    suspend fun createTask(request: CreateTaskRequest): TaskUiModel
    suspend fun listTasks(): List<TaskUiModel>
    suspend fun getTask(taskId: String): TaskUiModel?
    fun setActiveTask(taskId: String)
    suspend fun listAnomalies(taskId: String): List<AnomalyUiModel>
    suspend fun submitAnomaly(request: SubmitAnomalyRequest): String
    suspend fun reviewAnomaly(id: String, request: ReviewAnomalyRequest)
    suspend fun updateAnomaly(id: String, fields: AnomalyUpdateRequest)
    suspend fun attachPhoto(anomalyId: String, uri: Uri)
    suspend fun exportReport(taskId: String, formats: Set<ReportFormat>): ReportExportResult
    fun startDisasterTrack()
    fun addDisasterPoint(lat: Double, lng: Double)
    suspend fun captureCurrentLocation()
    fun finishDisasterTrack()
    fun resetDisasterTrack()
    fun setCompareImages(historicalUri: Uri?, currentUri: Uri?)
    fun setCompareSlider(value: Float)
    fun refineMaskAt(x: Float, y: Float)
    fun setMapLayerVisibility(showOrtho: Boolean, showMask: Boolean)
    fun setMaskAlpha(alpha: Float)
    fun clearMapSession()
    fun selectAnomaly(id: String?)
    suspend fun refreshAnomalyLocations()
    fun refreshHistory()
    fun close()
}

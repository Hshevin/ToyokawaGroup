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
        val MOBILE_SAM = ModelChoice(
            key = "mobile_sam",
            label = "MobileSAM",
            specAsset = "models/mobile_sam_interactive_v1/model_spec.json",
        )

        /** 检测页可见模型；MobileSAM 作为 Building 检测后的修正引擎，不在 UI 切换。 */
        val ALL = listOf(BUILDING)

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
    fun setMapLayerVisibility(showOrtho: Boolean, showMask: Boolean)
    fun setMaskAlpha(alpha: Float)
    fun clearMapSession()
    suspend fun benchmark(uri: Uri, runs: Int = 10)
    fun refreshHistory()
    fun close()
}

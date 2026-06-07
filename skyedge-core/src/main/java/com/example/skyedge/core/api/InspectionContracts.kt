package com.example.skyedge.core.api

import android.net.Uri
import kotlinx.coroutines.flow.StateFlow

data class InspectionUiState(
    val statusMessage: String = "正在加载模型…",
    val isLoadingModel: Boolean = true,
    val isInferring: Boolean = false,
    val isModelReady: Boolean = false,
    val lastMaskPath: String? = null,
    val selectedModelKey: String = ModelChoice.BUILDING.key,
    val recentRecords: List<InspectionRecordItem> = emptyList(),
    val mapSession: MapSessionUiModel? = null,
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
        val ROAD = ModelChoice(
            key = "road",
            label = "Road",
            specAsset = "models/road_unet_efficientnetb0_v1/model_spec.json",
        )

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
    suspend fun loadGeoTiff(uri: Uri)
    suspend fun inferMapSession()
    fun setMapLayerVisibility(showOrtho: Boolean, showMask: Boolean)
    fun setMaskAlpha(alpha: Float)
    fun clearMapSession()
    suspend fun benchmark(uri: Uri, runs: Int = 10)
    fun refreshHistory()
    fun close()
}

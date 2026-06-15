package com.example.skyedge.core.geo

import android.graphics.Bitmap

data class GeoLatLng(
    val latitude: Double,
    val longitude: Double,
)

data class GeoBounds(
    val sw: GeoLatLng,
    val ne: GeoLatLng,
) {
    companion object {
        fun envelopeFromCorners(corners: List<GeoLatLng>): GeoBounds {
            require(corners.isNotEmpty()) { "corners must not be empty" }
            val minLat = corners.minOf { it.latitude }
            val maxLat = corners.maxOf { it.latitude }
            val minLng = corners.minOf { it.longitude }
            val maxLng = corners.maxOf { it.longitude }
            return GeoBounds(
                sw = GeoLatLng(latitude = minLat, longitude = minLng),
                ne = GeoLatLng(latitude = maxLat, longitude = maxLng),
            )
        }
    }
}

data class GeoMetadata(
    val crs: String,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val previewWidth: Int,
    val previewHeight: Int,
    val modelInputWidth: Int,
    val modelInputHeight: Int,
    val boundsWgs84: GeoBounds,
    val boundsGcj02: GeoBounds,
    val geoAffine: GeoAffine? = null,
    val orthoPreviewPath: String? = null,
    val maskOverlayPath: String? = null,
)

data class GeoRasterLoadResult(
    val previewBitmap: Bitmap,
    val metadata: GeoMetadata,
)

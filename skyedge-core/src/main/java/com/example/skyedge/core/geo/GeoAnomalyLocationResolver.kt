package com.example.skyedge.core.geo

import com.example.skyedge.core.api.BoundingBoxDto

/**
 * Maps a normalized mask bbox to geographic coordinates for GeoTIFF-backed sessions.
 *
 * Coordinate chain: mask pixel → preview pixel → source pixel → WGS84 (GeoAffine) → GCJ-02 label.
 */
object GeoAnomalyLocationResolver {

    fun resolve(
        metadata: GeoMetadata,
        bbox: BoundingBoxDto,
        maskWidth: Int,
        maskHeight: Int,
    ): String {
        if (maskWidth <= 0 || maskHeight <= 0) return fallbackPercentLabel(bbox)
        val (sourceX, sourceY) = sourcePixelForBboxCenter(metadata, bbox, maskWidth, maskHeight)
        val wgs84 = metadata.geoAffine?.pointForPixel(sourceX, sourceY)
            ?: latLngFromBoundsFraction(metadata.boundsWgs84, bbox)
        return formatLocation(wgs84)
    }

    fun sourcePixelForBboxCenter(
        metadata: GeoMetadata,
        bbox: BoundingBoxDto,
        maskWidth: Int,
        maskHeight: Int,
    ): Pair<Double, Double> {
        val cxNorm = (bbox.x + bbox.width / 2f).toDouble()
        val cyNorm = (bbox.y + bbox.height / 2f).toDouble()
        val cxMask = cxNorm * maskWidth
        val cyMask = cyNorm * maskHeight
        val previewW = metadata.previewWidth.coerceAtLeast(1)
        val previewH = metadata.previewHeight.coerceAtLeast(1)
        val sourceW = metadata.sourceWidth.coerceAtLeast(1)
        val sourceH = metadata.sourceHeight.coerceAtLeast(1)
        val cxPreview = cxMask * previewW / maskWidth
        val cyPreview = cyMask * previewH / maskHeight
        val cxSource = cxPreview * sourceW / previewW
        val cySource = cyPreview * sourceH / previewH
        return cxSource to cySource
    }

    /** Fallback when geo affine is missing from legacy geo.json files. */
    fun latLngFromBoundsFraction(bounds: GeoBounds, bbox: BoundingBoxDto): GeoLatLng {
        val cx = (bbox.x + bbox.width / 2f).toDouble().coerceIn(0.0, 1.0)
        val cy = (bbox.y + bbox.height / 2f).toDouble().coerceIn(0.0, 1.0)
        val latSpan = bounds.ne.latitude - bounds.sw.latitude
        val lngSpan = bounds.ne.longitude - bounds.sw.longitude
        return GeoLatLng(
            latitude = bounds.ne.latitude - cy * latSpan,
            longitude = bounds.sw.longitude + cx * lngSpan,
        )
    }

    fun formatLocation(wgs84: GeoLatLng): String {
        val gcj02 = CoordinateConverter.wgs84ToGcj02(wgs84)
        val coords = formatCoordPair(gcj02)
        return if (usesGcj02(wgs84, gcj02)) {
            "GCJ-02 $coords"
        } else {
            "WGS84 $coords"
        }
    }

    fun fallbackPercentLabel(bbox: BoundingBoxDto): String {
        val cx = ((bbox.x + bbox.width / 2f) * 100).toInt().coerceIn(0, 100)
        val cy = ((bbox.y + bbox.height / 2f) * 100).toInt().coerceIn(0, 100)
        return "画面区域 中心约 ($cx%, $cy%)"
    }

    fun looksLikePercentFallback(location: String): Boolean =
        location.isBlank() || location.startsWith("画面区域")

    private fun formatCoordPair(point: GeoLatLng): String {
        val latHemisphere = if (point.latitude >= 0) "N" else "S"
        val lngHemisphere = if (point.longitude >= 0) "E" else "W"
        return "${formatDegree(point.latitude)}°$latHemisphere, ${formatDegree(point.longitude)}°$lngHemisphere"
    }

    private fun formatDegree(value: Double): String =
        "%.6f".format(kotlin.math.abs(value))

    private fun usesGcj02(wgs84: GeoLatLng, gcj02: GeoLatLng): Boolean =
        kotlin.math.abs(wgs84.latitude - gcj02.latitude) > 1e-8 ||
            kotlin.math.abs(wgs84.longitude - gcj02.longitude) > 1e-8
}

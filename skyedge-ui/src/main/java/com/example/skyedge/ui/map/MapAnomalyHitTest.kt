package com.example.skyedge.ui.map

import com.example.skyedge.core.api.AnomalyUiModel
import com.example.skyedge.core.api.MapSessionUiModel

object MapAnomalyHitTest {
    fun hitTest(
        latitude: Double,
        longitude: Double,
        session: MapSessionUiModel?,
        anomalies: List<AnomalyUiModel>,
    ): AnomalyUiModel? {
        if (session == null) return null
        val bounds = session.boundsGcj02
        val lngSpan = bounds.ne.lng - bounds.sw.lng
        val latSpan = bounds.ne.lat - bounds.sw.lat
        if (lngSpan == 0.0 || latSpan == 0.0) return null

        return anomalies
            .filter { anomaly ->
                val leftLng = bounds.sw.lng + anomaly.bbox.x * lngSpan
                val rightLng = bounds.sw.lng + (anomaly.bbox.x + anomaly.bbox.width) * lngSpan
                val topLat = bounds.ne.lat - anomaly.bbox.y * latSpan
                val bottomLat = bounds.ne.lat - (anomaly.bbox.y + anomaly.bbox.height) * latSpan
                longitude >= minOf(leftLng, rightLng) &&
                    longitude <= maxOf(leftLng, rightLng) &&
                    latitude >= minOf(bottomLat, topLat) &&
                    latitude <= maxOf(bottomLat, topLat)
            }
            .minByOrNull { it.bbox.width * it.bbox.height }
    }
}

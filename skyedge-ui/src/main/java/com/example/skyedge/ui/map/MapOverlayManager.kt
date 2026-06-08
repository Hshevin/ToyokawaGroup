package com.example.skyedge.ui.map

import android.graphics.BitmapFactory
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.GroundOverlay
import com.amap.api.maps.model.GroundOverlayOptions
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.example.skyedge.core.api.GeoBoundsDto
import com.example.skyedge.core.api.MapSessionUiModel
import java.io.File

class MapOverlayManager(private val map: AMap) {
    private var currentSessionId: String? = null
    private var orthoOverlay: GroundOverlay? = null
    private var maskOverlay: GroundOverlay? = null

    fun render(session: MapSessionUiModel?) {
        if (session == null) {
            clear()
            return
        }
        val bounds = session.boundsGcj02.toAmapBounds()
        if (currentSessionId != session.sessionId) {
            clear()
            currentSessionId = session.sessionId
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, CAMERA_PADDING))
        }
        orthoOverlay = upsertOverlay(
            existing = orthoOverlay,
            path = session.orthoPreviewPath,
            bounds = bounds,
            visible = session.showOrtho,
            transparency = 0f,
        )
        maskOverlay = upsertOverlay(
            existing = maskOverlay,
            path = session.maskOverlayPath,
            bounds = bounds,
            visible = session.showMask && !session.maskOverlayPath.isNullOrBlank(),
            transparency = 1f - session.maskAlpha.coerceIn(0f, 1f),
        )
    }

    fun clear() {
        orthoOverlay?.remove()
        maskOverlay?.remove()
        orthoOverlay = null
        maskOverlay = null
        currentSessionId = null
    }

    private fun upsertOverlay(
        existing: GroundOverlay?,
        path: String?,
        bounds: LatLngBounds,
        visible: Boolean,
        transparency: Float,
    ): GroundOverlay? {
        if (path.isNullOrBlank() || !File(path).exists()) {
            existing?.remove()
            return null
        }
        val overlay = existing ?: createOverlay(path, bounds)
        overlay.setPositionFromBounds(bounds)
        overlay.isVisible = visible
        overlay.transparency = transparency.coerceIn(0f, 1f)
        return overlay
    }

    private fun createOverlay(path: String, bounds: LatLngBounds): GroundOverlay {
        val bitmap = BitmapFactory.decodeFile(path)
            ?: error("无法读取地图叠加图层: $path")
        return map.addGroundOverlay(
            GroundOverlayOptions()
                .image(BitmapDescriptorFactory.fromBitmap(bitmap))
                .positionFromBounds(bounds),
        ).also {
            bitmap.recycle()
        }
    }

    private fun GeoBoundsDto.toAmapBounds(): LatLngBounds =
        LatLngBounds.builder()
            .include(LatLng(sw.lat, sw.lng))
            .include(LatLng(ne.lat, ne.lng))
            .build()

    private companion object {
        private const val CAMERA_PADDING = 80
    }
}

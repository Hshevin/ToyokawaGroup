package com.example.skyedge.ui.map

import android.net.Uri
import com.example.skyedge.ui.inspection.InferenceViewModel

class MapViewModel(
    private val delegate: InferenceViewModel,
) {
    fun switchModel(modelKey: String) {
        delegate.switchModel(modelKey)
    }

    fun loadGeoTiff(uri: Uri) {
        delegate.loadGeoTiff(uri)
    }

    fun inferMapSession() {
        delegate.inferMapSession()
    }

    fun setLayerVisibility(showOrtho: Boolean, showMask: Boolean) {
        delegate.setMapLayerVisibility(showOrtho, showMask)
    }

    fun setMaskAlpha(alpha: Float) {
        delegate.setMaskAlpha(alpha)
    }

    fun clearMapSession() {
        delegate.clearMapSession()
    }
}

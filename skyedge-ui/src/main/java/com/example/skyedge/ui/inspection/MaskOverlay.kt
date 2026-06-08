package com.example.skyedge.ui.inspection

import android.graphics.Bitmap
import com.example.skyedge.core.model.MaskOverlayRenderer

fun buildMaskOverlay(maskPath: String?, modelKey: String): Bitmap? {
    if (maskPath.isNullOrBlank()) return null
    return runCatching { MaskOverlayRenderer.renderMaskBitmap(maskPath, modelKey) }.getOrNull()
}

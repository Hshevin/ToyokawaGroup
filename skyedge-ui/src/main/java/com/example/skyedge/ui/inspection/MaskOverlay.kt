package com.example.skyedge.ui.inspection

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color

fun buildMaskOverlay(maskPath: String?): Bitmap? {
    if (maskPath.isNullOrBlank()) return null
    val mask = BitmapFactory.decodeFile(maskPath) ?: return null
    val width = mask.width
    val height = mask.height
    val src = IntArray(width * height)
    val dst = IntArray(width * height)
    mask.getPixels(src, 0, width, 0, 0, width, height)
    for (i in src.indices) {
        val gray = src[i] and 0xFF
        dst[i] = if (gray > 0) {
            Color.argb((0.42f * 255).toInt(), 255, 0, 0)
        } else {
            Color.TRANSPARENT
        }
    }
    val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    overlay.setPixels(dst, 0, width, 0, 0, width, height)
    mask.recycle()
    return overlay
}

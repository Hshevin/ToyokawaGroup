package com.example.skyedge.core.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.io.FileOutputStream

object MaskOverlayRenderer {
    private const val DEFAULT_ALPHA = 0.55f

    fun renderMaskFile(
        maskPath: String,
        outputFile: File,
        targetWidth: Int? = null,
        targetHeight: Int? = null,
        modelKey: String = "building",
        alpha: Float = DEFAULT_ALPHA,
    ): String {
        val mask = BitmapFactory.decodeFile(maskPath)
            ?: error("无法读取 mask: $maskPath")
        return try {
            val width = targetWidth ?: mask.width
            val height = targetHeight ?: mask.height
            val classes = if (width == mask.width && height == mask.height) {
                MaskResizer.resizeGrayMask(mask, mask.width, mask.height)
            } else {
                MaskResizer.resizeGrayMask(mask, width, height)
            }
            val overlay = renderClassMask(classes, width, height, modelKey, alpha)
            writePng(overlay, outputFile)
        } finally {
            mask.recycle()
        }
    }

    fun renderMaskBitmap(
        maskPath: String,
        modelKey: String = "building",
        alpha: Float = DEFAULT_ALPHA,
    ): Bitmap {
        val mask = BitmapFactory.decodeFile(maskPath)
            ?: error("无法读取 mask: $maskPath")
        return try {
            val classes = MaskResizer.resizeGrayMask(mask, mask.width, mask.height)
            renderClassMask(classes, mask.width, mask.height, modelKey, alpha)
        } finally {
            mask.recycle()
        }
    }

    fun renderClassMask(
        classIndices: IntArray,
        width: Int,
        height: Int,
        modelKey: String,
        alpha: Float = DEFAULT_ALPHA,
    ): Bitmap {
        require(classIndices.size == width * height) {
            "classIndices size ${classIndices.size} != ${width}x$height"
        }
        val targetAlpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        val color = overlayColor(modelKey, targetAlpha)
        val pixels = IntArray(width * height)
        for (i in classIndices.indices) {
            pixels[i] = if (classIndices[i] > 0) color else Color.TRANSPARENT
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    private fun writePng(bitmap: Bitmap, outputFile: File): String {
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return outputFile.absolutePath
    }

    private fun overlayColor(modelKey: String, alpha: Int): Int =
        when (modelKey) {
            "building" -> Color.argb(alpha, 56, 178, 108)
            else -> Color.argb(alpha, 255, 48, 48)
        }
}

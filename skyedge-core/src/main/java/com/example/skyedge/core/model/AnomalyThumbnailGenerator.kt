package com.example.skyedge.core.model

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.example.skyedge.core.api.BoundingBoxDto
import java.io.File
import java.io.FileOutputStream

object AnomalyThumbnailGenerator {
    fun generate(
        context: Context,
        imageUri: String?,
        bbox: BoundingBoxDto,
        outputFile: File,
        maxEdge: Int = 320,
    ): String {
        if (imageUri.isNullOrBlank()) return ""
        val source = ImagePreprocessor.loadOrientedBitmap(context, Uri.parse(imageUri)) ?: return ""
        return try {
            val cropped = crop(source, bbox)
            val scaled = scaleDown(cropped, maxEdge)
            outputFile.parentFile?.mkdirs()
            FileOutputStream(outputFile).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
            }
            if (scaled !== cropped) scaled.recycle()
            cropped.recycle()
            outputFile.absolutePath
        } finally {
            source.recycle()
        }
    }

    private fun crop(source: Bitmap, bbox: BoundingBoxDto): Bitmap {
        val left = (bbox.x.coerceIn(0f, 1f) * source.width).toInt().coerceIn(0, source.width - 1)
        val top = (bbox.y.coerceIn(0f, 1f) * source.height).toInt().coerceIn(0, source.height - 1)
        val right = ((bbox.x + bbox.width).coerceIn(0f, 1f) * source.width).toInt().coerceIn(left + 1, source.width)
        val bottom = ((bbox.y + bbox.height).coerceIn(0f, 1f) * source.height).toInt().coerceIn(top + 1, source.height)
        return Bitmap.createBitmap(source, left, top, right - left, bottom - top)
    }

    private fun scaleDown(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxEdge) return bitmap
        val scale = maxEdge.toFloat() / longest.toFloat()
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }
}

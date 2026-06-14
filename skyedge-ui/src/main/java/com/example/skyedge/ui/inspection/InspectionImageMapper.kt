package com.example.skyedge.ui.inspection

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.math.max
import kotlin.math.min

object InspectionImageMapper {
    data class FitTransform(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
        val drawnWidth: Float,
        val drawnHeight: Float,
    )

    fun fitTransform(viewSize: IntSize, imageWidth: Int, imageHeight: Int): FitTransform? {
        if (imageWidth <= 0 || imageHeight <= 0 || viewSize.width <= 0 || viewSize.height <= 0) {
            return null
        }
        val boxWidth = viewSize.width.toFloat()
        val boxHeight = viewSize.height.toFloat()
        val scale = min(boxWidth / imageWidth, boxHeight / imageHeight)
        val drawnWidth = imageWidth * scale
        val drawnHeight = imageHeight * scale
        val offsetX = (boxWidth - drawnWidth) / 2f
        val offsetY = (boxHeight - drawnHeight) / 2f
        return FitTransform(scale, offsetX, offsetY, drawnWidth, drawnHeight)
    }

    fun mapToImagePixel(
        touch: Offset,
        viewSize: IntSize,
        imageWidth: Int,
        imageHeight: Int,
    ): Pair<Float, Float>? {
        val fit = fitTransform(viewSize, imageWidth, imageHeight) ?: return null
        if (touch.x < fit.offsetX || touch.y < fit.offsetY ||
            touch.x > fit.offsetX + fit.drawnWidth || touch.y > fit.offsetY + fit.drawnHeight
        ) {
            return null
        }
        val x = ((touch.x - fit.offsetX) / fit.scale).coerceIn(0f, imageWidth.toFloat())
        val y = ((touch.y - fit.offsetY) / fit.scale).coerceIn(0f, imageHeight.toFloat())
        return x to y
    }

    fun mapToViewOffset(
        imageX: Float,
        imageY: Float,
        viewSize: IntSize,
        imageWidth: Int,
        imageHeight: Int,
    ): Offset? {
        val fit = fitTransform(viewSize, imageWidth, imageHeight) ?: return null
        return Offset(
            fit.offsetX + imageX * fit.scale,
            fit.offsetY + imageY * fit.scale,
        )
    }

    fun boxFromDrag(
        start: Offset,
        end: Offset,
        viewSize: IntSize,
        imageWidth: Int,
        imageHeight: Int,
    ): FloatArray? {
        val first = mapToImagePixel(start, viewSize, imageWidth, imageHeight) ?: return null
        val second = mapToImagePixel(end, viewSize, imageWidth, imageHeight) ?: return null
        return floatArrayOf(
            min(first.first, second.first),
            min(first.second, second.second),
            max(first.first, second.first),
            max(first.second, second.second),
        )
    }
}

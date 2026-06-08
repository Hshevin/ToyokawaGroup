package com.example.skyedge.core.model

import android.graphics.Bitmap

object MaskResizer {
    fun resizeClassIndices(
        classIndices: IntArray,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
    ): IntArray {
        require(classIndices.size == sourceWidth * sourceHeight) {
            "classIndices size ${classIndices.size} != ${sourceWidth}x$sourceHeight"
        }
        val resized = IntArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sourceY = (y * sourceHeight / targetHeight).coerceAtMost(sourceHeight - 1)
            for (x in 0 until targetWidth) {
                val sourceX = (x * sourceWidth / targetWidth).coerceAtMost(sourceWidth - 1)
                resized[y * targetWidth + x] = classIndices[sourceY * sourceWidth + sourceX]
            }
        }
        return resized
    }

    fun resizeGrayMask(mask: Bitmap, targetWidth: Int, targetHeight: Int): IntArray {
        val sourceWidth = mask.width
        val sourceHeight = mask.height
        val src = IntArray(sourceWidth * sourceHeight)
        mask.getPixels(src, 0, sourceWidth, 0, 0, sourceWidth, sourceHeight)
        val resized = IntArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sourceY = (y * sourceHeight / targetHeight).coerceAtMost(sourceHeight - 1)
            for (x in 0 until targetWidth) {
                val sourceX = (x * sourceWidth / targetWidth).coerceAtMost(sourceWidth - 1)
                resized[y * targetWidth + x] = src[sourceY * sourceWidth + sourceX] and 0xFF
            }
        }
        return resized
    }
}

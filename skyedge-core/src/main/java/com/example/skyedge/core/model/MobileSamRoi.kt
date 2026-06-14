package com.example.skyedge.core.model

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.max
import kotlin.math.min

data class MobileSamRoiBox(
    val x1: Int,
    val y1: Int,
    val x2: Int,
    val y2: Int,
) {
    val width: Int get() = (x2 - x1).coerceAtLeast(1)
    val height: Int get() = (y2 - y1).coerceAtLeast(1)

    companion object {
        fun fromJsonArray(values: org.json.JSONArray, imageWidth: Int, imageHeight: Int): MobileSamRoiBox {
            val raw = IntArray(4) { idx -> values.getInt(idx) }
            return clamp(raw[0], raw[1], raw[2], raw[3], imageWidth, imageHeight)
        }

        fun clamp(
            x1: Int,
            y1: Int,
            x2: Int,
            y2: Int,
            imageWidth: Int,
            imageHeight: Int,
        ): MobileSamRoiBox {
            val left = x1.coerceIn(0, imageWidth - 1)
            val top = y1.coerceIn(0, imageHeight - 1)
            val right = x2.coerceIn(left + 1, imageWidth)
            val bottom = y2.coerceIn(top + 1, imageHeight)
            return MobileSamRoiBox(left, top, right, bottom)
        }
    }
}

object MobileSamRoi {
    fun cropBitmap(source: Bitmap, box: MobileSamRoiBox): Bitmap =
        Bitmap.createBitmap(source, box.x1, box.y1, box.width, box.height)

    fun boxFromMaskFile(
        maskFile: File,
        imageWidth: Int,
        imageHeight: Int,
        padRatio: Float = 0.08f,
        minPadPx: Int = 8,
    ): MobileSamRoiBox? {
        if (!maskFile.exists()) return null
        val mask = BitmapFactory.decodeFile(maskFile.absolutePath) ?: return null
        return try {
            boxFromMaskBitmap(mask, imageWidth, imageHeight, padRatio, minPadPx)
        } finally {
            mask.recycle()
        }
    }

    fun boxFromMaskBitmap(
        mask: Bitmap,
        imageWidth: Int,
        imageHeight: Int,
        padRatio: Float = 0.08f,
        minPadPx: Int = 8,
    ): MobileSamRoiBox? {
        val width = min(mask.width, imageWidth)
        val height = min(mask.height, imageHeight)
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1
        val row = IntArray(width)
        for (y in 0 until height) {
            mask.getPixels(row, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val pixel = row[x]
                val value = pixel and 0xFF
                if (value > 127) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < minX || maxY < minY) return null
        val pad = max(minPadPx, (max(maxX - minX, maxY - minY) * padRatio).toInt())
        return MobileSamRoiBox.clamp(
            x1 = minX - pad,
            y1 = minY - pad,
            x2 = maxX + pad + 1,
            y2 = maxY + pad + 1,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
    }

    /** 框选后优先落在框内已有 mask 的质心，避免几何中心落在背景上。 */
    fun promptPointInBox(
        maskFile: File?,
        box: MobileSamRoiBox,
        imageWidth: Int,
        imageHeight: Int,
    ): Pair<Float, Float> {
        val fallbackX = (box.x1 + box.x2) / 2f
        val fallbackY = (box.y1 + box.y2) / 2f
        if (maskFile == null || !maskFile.exists()) return fallbackX to fallbackY
        val mask = BitmapFactory.decodeFile(maskFile.absolutePath) ?: return fallbackX to fallbackY
        return try {
            val xEnd = min(box.x2, mask.width)
            val yEnd = min(box.y2, mask.height)
            var sumX = 0L
            var sumY = 0L
            var count = 0
            for (y in box.y1 until yEnd) {
                for (x in box.x1 until xEnd) {
                    if (mask.getPixel(x, y) and 0xFF > 127) {
                        sumX += x
                        sumY += y
                        count++
                    }
                }
            }
            if (count > 0) {
                (sumX.toFloat() / count) to (sumY.toFloat() / count)
            } else {
                fallbackX to fallbackY
            }
        } finally {
            mask.recycle()
        }
    }

    fun pasteCropMaskToFull(
        cropIndices: IntArray,
        cropWidth: Int,
        cropHeight: Int,
        fullWidth: Int,
        fullHeight: Int,
        offsetX: Int,
        offsetY: Int,
    ): IntArray {
        require(cropIndices.size == cropWidth * cropHeight) {
            "crop mask size mismatch: ${cropIndices.size} != ${cropWidth}x$cropHeight"
        }
        val full = IntArray(fullWidth * fullHeight)
        for (y in 0 until cropHeight) {
            val destY = offsetY + y
            if (destY !in 0 until fullHeight) continue
            for (x in 0 until cropWidth) {
                val destX = offsetX + x
                if (destX !in 0 until fullWidth) continue
                full[destY * fullWidth + destX] = cropIndices[y * cropWidth + x]
            }
        }
        return full
    }
}

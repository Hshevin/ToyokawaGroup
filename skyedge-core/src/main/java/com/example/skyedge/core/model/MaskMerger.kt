package com.example.skyedge.core.model

import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.min

object MaskMerger {
    /**
     * 局部修正时保留框外已有 mask；框内用 SAM 新结果（可与原 mask 取并集）。
     */
    fun mergeWithPrevious(
        previousMaskPath: String?,
        updatedIndices: IntArray,
        width: Int,
        height: Int,
        roi: MobileSamRoiBox?,
    ): IntArray {
        require(updatedIndices.size == width * height) {
            "updated mask size ${updatedIndices.size} != ${width}x$height"
        }
        if (previousMaskPath.isNullOrBlank()) return updatedIndices
        val previousFile = File(previousMaskPath)
        if (!previousFile.exists()) return updatedIndices

        val previousBitmap = BitmapFactory.decodeFile(previousMaskPath) ?: return updatedIndices
        return try {
            val previous = MaskResizer.resizeGrayMask(previousBitmap, width, height)
            IntArray(updatedIndices.size) { index ->
                val x = index % width
                val y = index / width
                val previousForeground = previous[index] > 127
                val updatedForeground = updatedIndices[index] > 0
                val insideRoi = roi?.contains(x, y) == true
                when {
                    roi != null && !insideRoi -> if (previousForeground) 1 else 0
                    updatedForeground || previousForeground -> 1
                    else -> 0
                }
            }
        } finally {
            previousBitmap.recycle()
        }
    }

    /**
     * 框选时选中框内所有 Building 已检测区域，并与整图已有 mask 合并。
     */
    fun buildBoxSelectionFromBuilding(
        buildingMaskPath: String,
        box: MobileSamRoiBox,
        width: Int,
        height: Int,
    ): IntArray? {
        val building = loadClassIndices(buildingMaskPath, width, height) ?: return null
        val boxSelection = IntArray(width * height)
        val xEnd = min(box.x2, width)
        val yEnd = min(box.y2, height)
        for (y in box.y1 until yEnd) {
            for (x in box.x1 until xEnd) {
                if (building[y * width + x] > 0) {
                    boxSelection[y * width + x] = 1
                }
            }
        }
        return mergeWithPrevious(buildingMaskPath, boxSelection, width, height, roi = box)
    }

    fun countForegroundInBox(classIndices: IntArray, width: Int, box: MobileSamRoiBox): Int {
        val xEnd = min(box.x2, width)
        val yEnd = min(box.y2, classIndices.size / width)
        var count = 0
        for (y in box.y1 until yEnd) {
            for (x in box.x1 until xEnd) {
                if (classIndices[y * width + x] > 0) count++
            }
        }
        return count
    }

    fun defectAreaRatio(classIndices: IntArray): Float =
        classIndices.count { it > 0 }.toFloat() / classIndices.size.toFloat()

    private fun loadClassIndices(maskPath: String, width: Int, height: Int): IntArray? {
        val bitmap = BitmapFactory.decodeFile(maskPath) ?: return null
        return try {
            MaskResizer.resizeGrayMask(bitmap, width, height).map { if (it > 127) 1 else 0 }.toIntArray()
        } finally {
            bitmap.recycle()
        }
    }
}

private fun MobileSamRoiBox.contains(x: Int, y: Int): Boolean =
    x in x1 until x2 && y in y1 until y2

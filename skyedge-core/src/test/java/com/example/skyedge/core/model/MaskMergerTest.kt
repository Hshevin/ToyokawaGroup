package com.example.skyedge.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MaskMergerTest {

    @Test
    fun mergeWithPrevious_unionsInsideRoi() {
        val width = 4
        val height = 4
        val previous = IntArray(width * height) { index ->
            if (index in 0 until 8) 1 else 0
        }
        val updated = IntArray(width * height) { index ->
            if (index == 10) 1 else 0
        }
        val roi = MobileSamRoiBox(0, 0, 4, 4)

        val merged = MaskMerger.mergeWithPrevious(
            previousMaskPath = writeTempMask(previous, width, height),
            updatedIndices = updated,
            width = width,
            height = height,
            roi = roi,
        )

        assertEquals(1, merged[0])
        assertEquals(1, merged[10])
    }

    @Test
    fun mergeWithPrevious_withoutRoi_unionsGlobally() {
        val width = 3
        val height = 3
        val previous = IntArray(width * height) { if (it == 0) 1 else 0 }
        val updated = IntArray(width * height) { if (it == 8) 1 else 0 }

        val merged = MaskMerger.mergeWithPrevious(
            previousMaskPath = writeTempMask(previous, width, height),
            updatedIndices = updated,
            width = width,
            height = height,
            roi = null,
        )

        assertEquals(1, merged[0])
        assertEquals(1, merged[8])
    }

    @Test
    fun buildBoxSelectionFromMask_keepsOutsideBox() {
        val width = 6
        val height = 6
        val previous = IntArray(width * height) { index ->
            when (index) {
                0, 1, 20, 21 -> 1
                else -> 0
            }
        }
        val maskPath = writeTempMask(previous, width, height)
        val box = MobileSamRoiBox(2, 2, 5, 5)

        val merged = MaskMerger.buildBoxSelectionFromMask(maskPath, box, width, height)
        requireNotNull(merged)

        assertEquals(1, merged[0])
        assertEquals(1, merged[20])
    }

    private fun writeTempMask(classIndices: IntArray, width: Int, height: Int): String {
        val file = File.createTempFile("mask-merger", ".png")
        MaskWriter.writeClassIndices(classIndices, width, height, file)
        return file.absolutePath
    }
}

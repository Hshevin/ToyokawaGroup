package com.example.skyedge.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MobileSamRoiTest {

    @Test
    fun pasteCropMaskToFull_placesCropIntoCanvas() {
        val crop = intArrayOf(
            1, 0,
            0, 1,
        )
        val full = MobileSamRoi.pasteCropMaskToFull(
            cropIndices = crop,
            cropWidth = 2,
            cropHeight = 2,
            fullWidth = 4,
            fullHeight = 3,
            offsetX = 1,
            offsetY = 1,
        )
        assertEquals(0, full[0])
        assertEquals(1, full[1 * 4 + 1])
        assertEquals(1, full[2 * 4 + 2])
    }

    @Test
    fun clamp_keepsBoxInsideImage() {
        val box = MobileSamRoiBox.clamp(-5, -5, 999, 999, imageWidth = 10, imageHeight = 8)
        assertEquals(0, box.x1)
        assertEquals(0, box.y1)
        assertEquals(10, box.x2)
        assertEquals(8, box.y2)
    }
}

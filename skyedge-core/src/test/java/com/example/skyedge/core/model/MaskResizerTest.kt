package com.example.skyedge.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class MaskResizerTest {
    @Test
    fun resizeClassIndices_usesNearestNeighbor() {
        val resized = MaskResizer.resizeClassIndices(
            classIndices = intArrayOf(
                0, 1,
                2, 3,
            ),
            sourceWidth = 2,
            sourceHeight = 2,
            targetWidth = 4,
            targetHeight = 4,
        )

        assertArrayEquals(
            intArrayOf(
                0, 0, 1, 1,
                0, 0, 1, 1,
                2, 2, 3, 3,
                2, 2, 3, 3,
            ),
            resized,
        )
    }
}

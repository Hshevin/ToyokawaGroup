package com.example.skyedge.core.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class TiffLzwStripResourceTest {
    @Test
    fun decompress_img1_strip0_fromResource() {
        decompressResource("lzw_img1_strip0.bin", 1256, 1467)
    }

    @Test
    fun decompress_img10_strip0_fromResource() {
        decompressResource("lzw_img10_strip0.bin", 1498, 1467)
    }

    private fun decompressResource(name: String, stripSize: Int, expectedSize: Int) {
        val strip = javaClass.classLoader
            ?.getResourceAsStream(name)
            ?.use { it.readBytes() }
            ?: error("missing $name")
        assertEquals(stripSize, strip.size)
        val decoded = TiffLzwDecompressor.decompress(strip, expectedSize = expectedSize)
        assertEquals(expectedSize, decoded.size)
    }
}

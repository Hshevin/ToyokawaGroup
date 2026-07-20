package com.example.skyedge.core.geo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TiffLzwStripDeviceTest {
    @Test
    fun decompress_img10_strip0_onDevice() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val strip = context.assets.open("lzw_img10_strip0.bin").use { it.readBytes() }
        assertEquals(1498, strip.size)
        val decoded = TiffLzwDecompressor.decompress(strip, expectedSize = 1467)
        assertEquals(1467, decoded.size)
    }
}

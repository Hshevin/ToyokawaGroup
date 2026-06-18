package com.example.skyedge.core.geo

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.skyedge.core.geo.GeoTiffReader.decode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeoTiffDeviceDecodeTest {
    @Test
    fun decodeBundledImg10_onDevice() {
        val context = InstrumentationRegistry.getInstrumentation().context
        val bytes = context.assets.open("geotiff/SN2_buildings_train_AOI_2_Vegas_RGB_img10.tif").use { it.readBytes() }
        assertEquals(78367, bytes.size)
        val result = decode(bytes, maxEdge = 512)
        assertEquals(163, result.previewBitmap.width)
        assertEquals(163, result.previewBitmap.height)
    }
}

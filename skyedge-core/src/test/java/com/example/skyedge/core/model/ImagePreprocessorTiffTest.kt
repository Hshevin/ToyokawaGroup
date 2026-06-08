package com.example.skyedge.core.model

import android.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImagePreprocessorTiffTest {
    @Test
    fun tiffBitmapLoader_readsLzwCompressedSample() {
        val sample = File("../../test_img/building/SN2_buildings_train_AOI_2_Vegas_RGB_img1.tif")
        assumeTrue("需要 test_img 样本", sample.exists())

        val bitmap = TiffBitmapLoader.decode(sample.readBytes())

        assertNotNull(bitmap)
        assertEquals(163, bitmap!!.width)
        assertEquals(163, bitmap.height)
        assertEquals(Color.rgb(0, 0, 0), bitmap.getPixel(0, 0))
    }
}

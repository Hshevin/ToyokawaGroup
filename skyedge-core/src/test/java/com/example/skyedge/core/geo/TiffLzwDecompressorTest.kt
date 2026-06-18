package com.example.skyedge.core.geo

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test

class TiffLzwDecompressorTest {
    @Test
    fun decompress_matchesStripSizesForSpaceNetSample() {
        val sample = sequenceOf(
            File("../geotiff_map_test_samples/rgb_geotiff/building/SN2_buildings_train_AOI_2_Vegas_RGB_img1.tif"),
            File("../../test_img/building/SN2_buildings_train_AOI_2_Vegas_RGB_img1.tif"),
        ).firstOrNull { it.exists() }
        assumeTrue("需要 GeoTIFF 样本（geotiff_map_test_samples 或 test_img）", sample != null)

        val bytes = sample!!.readBytes()
        val parsed = TiffImageDecoder.parse(bytes)
        val stripOffsets = parsed.fields.longArray(TiffImageDecoder.TAG_STRIP_OFFSETS)
        val stripByteCounts = parsed.fields.longArray(TiffImageDecoder.TAG_STRIP_BYTE_COUNTS)
        val rowsPerStrip = parsed.fields.longValue(TiffImageDecoder.TAG_ROWS_PER_STRIP, parsed.height.toLong()).toInt()
        val rowStride = parsed.width * parsed.samplesPerPixel

        stripOffsets.indices.forEach { stripIndex ->
            val stripStart = stripOffsets[stripIndex].toInt()
            val stripSize = stripByteCounts[stripIndex].toInt()
            val rowsInStrip = minOf(rowsPerStrip, parsed.height - stripIndex * rowsPerStrip)
            val expectedSize = rowsInStrip * rowStride
            val compressed = bytes.copyOfRange(stripStart, stripStart + stripSize)
            val decoded = TiffLzwDecompressor.decompress(compressed, expectedSize)
            assertEquals(
                "strip $stripIndex 解压长度不匹配",
                expectedSize,
                decoded.size,
            )
        }
    }
}

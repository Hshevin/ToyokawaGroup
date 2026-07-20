package com.example.skyedge.core.geo

import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeoTiffSampleDecodeTest {
    @Test
    fun decodeAllRgbGeoTiffSamples() {
        val dir = File("../geotiff_map_test_samples/rgb_geotiff/building")
        assumeTrue("需要 rgb_geotiff 样本目录", dir.isDirectory)
        val failures = dir.listFiles { file -> file.extension.equals("tif", ignoreCase = true) }
            .orEmpty()
            .sortedBy { it.name }
            .mapNotNull { file ->
                runCatching { GeoTiffReader.decode(file.readBytes(), maxEdge = 512) }
                    .exceptionOrNull()
                    ?.let { file.name to it.message }
            }
        check(failures.isEmpty()) {
            failures.joinToString("\n") { (name, message) -> "$name: $message" }
        }
    }
}

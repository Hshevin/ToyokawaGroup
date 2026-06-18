package com.example.skyedge.core.geo

import android.graphics.Color
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GeoTiffReaderTest {
    @Test
    fun decode_readsLzwCompressedSpaceNetSample() {
        val sample = sequenceOf(
            File("../geotiff_map_test_samples/rgb_geotiff/building/SN2_buildings_train_AOI_2_Vegas_RGB_img1.tif"),
            File("../../test_img/building/SN2_buildings_train_AOI_2_Vegas_RGB_img1.tif"),
        ).firstOrNull { it.exists() }
        assumeTrue("需要 GeoTIFF 样本（geotiff_map_test_samples 或 test_img）", sample != null)

        val result = GeoTiffReader.decode(sample!!.readBytes(), maxEdge = 2048)

        assertEquals(163, result.previewBitmap.width)
        assertEquals(163, result.previewBitmap.height)
        assertEquals("EPSG:4326", result.metadata.crs)
        assertEquals(Color.rgb(0, 0, 0), result.previewBitmap.getPixel(0, 0))
    }

    @Test
    fun decode_readsRgbPreviewAndGeoBounds() {
        val result = GeoTiffReader.decode(minimalGeoTiff(), maxEdge = 2048)

        assertEquals(2, result.previewBitmap.width)
        assertEquals(2, result.previewBitmap.height)
        assertEquals(Color.rgb(255, 0, 0), result.previewBitmap.getPixel(0, 0))
        assertEquals(Color.rgb(255, 255, 255), result.previewBitmap.getPixel(1, 1))
        assertEquals("EPSG:4326", result.metadata.crs)
        assertEquals(120.0, result.metadata.boundsWgs84.sw.longitude, 0.0001)
        assertEquals(29.8, result.metadata.boundsWgs84.sw.latitude, 0.0001)
        assertEquals(120.2, result.metadata.boundsWgs84.ne.longitude, 0.0001)
        assertEquals(30.0, result.metadata.boundsWgs84.ne.latitude, 0.0001)
        val affine = requireNotNull(result.metadata.geoAffine)
        assertEquals(120.0, affine.originLongitude, 0.0001)
        assertEquals(0.1, affine.scaleX, 0.0001)
    }

    private fun minimalGeoTiff(): ByteArray {
        val bytes = ByteArray(512)
        writeAscii(bytes, 0, "II")
        writeShort(bytes, 2, 42)
        writeInt(bytes, 4, IFD_OFFSET)

        val entries = listOf(
            Entry(256, TYPE_LONG, 1, longValue(2)),
            Entry(257, TYPE_LONG, 1, longValue(2)),
            Entry(258, TYPE_SHORT, 3, shortArray(8, 8, 8)),
            Entry(259, TYPE_SHORT, 1, shortValue(1)),
            Entry(262, TYPE_SHORT, 1, shortValue(2)),
            Entry(273, TYPE_LONG, 1, longValue(IMAGE_OFFSET)),
            Entry(277, TYPE_SHORT, 1, shortValue(3)),
            Entry(278, TYPE_LONG, 1, longValue(2)),
            Entry(279, TYPE_LONG, 1, longValue(12)),
            Entry(284, TYPE_SHORT, 1, shortValue(1)),
            Entry(33550, TYPE_DOUBLE, 3, doubleArray(0.1, 0.1, 0.0)),
            Entry(33922, TYPE_DOUBLE, 6, doubleArray(0.0, 0.0, 0.0, 120.0, 30.0, 0.0)),
            Entry(34735, TYPE_SHORT, 8, shortArray(1, 1, 0, 1, 2048, 0, 1, 4326)),
        )

        writeShort(bytes, IFD_OFFSET, entries.size)
        var dataOffset = IFD_OFFSET + 2 + entries.size * 12 + 4
        entries.forEachIndexed { idx, entry ->
            val entryOffset = IFD_OFFSET + 2 + idx * 12
            writeShort(bytes, entryOffset, entry.tag)
            writeShort(bytes, entryOffset + 2, entry.type)
            writeInt(bytes, entryOffset + 4, entry.count)
            if (entry.data.size <= 4) {
                entry.data.copyInto(bytes, entryOffset + 8)
            } else {
                writeInt(bytes, entryOffset + 8, dataOffset)
                entry.data.copyInto(bytes, dataOffset)
                dataOffset += entry.data.size
            }
        }
        writeInt(bytes, IFD_OFFSET + 2 + entries.size * 12, 0)

        byteArrayOf(
            255.toByte(), 0, 0,
            0, 255.toByte(), 0,
            0, 0, 255.toByte(),
            255.toByte(), 255.toByte(), 255.toByte(),
        ).copyInto(bytes, IMAGE_OFFSET)
        return bytes
    }

    private data class Entry(
        val tag: Int,
        val type: Int,
        val count: Int,
        val data: ByteArray,
    )

    private fun shortValue(value: Int): ByteArray = ByteArray(4).also { writeShort(it, 0, value) }
    private fun longValue(value: Int): ByteArray = ByteArray(4).also { writeInt(it, 0, value) }
    private fun shortArray(vararg values: Int): ByteArray =
        ByteArray(values.size * 2).also { arr -> values.forEachIndexed { i, value -> writeShort(arr, i * 2, value) } }

    private fun doubleArray(vararg values: Double): ByteArray =
        ByteArray(values.size * 8).also { arr -> values.forEachIndexed { i, value -> writeDouble(arr, i * 8, value) } }

    private fun writeAscii(bytes: ByteArray, offset: Int, value: String) {
        value.encodeToByteArray().copyInto(bytes, offset)
    }

    private fun writeShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeInt(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun writeDouble(bytes: ByteArray, offset: Int, value: Double) {
        val bits = value.toBits()
        for (i in 0 until 8) {
            bytes[offset + i] = ((bits ushr (i * 8)) and 0xFF).toByte()
        }
    }

    private companion object {
        private const val IFD_OFFSET = 8
        private const val IMAGE_OFFSET = 400
        private const val TYPE_SHORT = 3
        private const val TYPE_LONG = 4
        private const val TYPE_DOUBLE = 12
    }
}

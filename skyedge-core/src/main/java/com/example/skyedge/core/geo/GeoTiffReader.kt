package com.example.skyedge.core.geo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.max

object GeoTiffReader {
    fun loadPreview(
        context: Context,
        uri: Uri,
        maxEdge: Int = 2048,
    ): Result<GeoRasterLoadResult> = runCatching {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("无法读取 GeoTIFF: $uri")
        decode(bytes, maxEdge)
    }

    fun decode(bytes: ByteArray, maxEdge: Int = 2048): GeoRasterLoadResult {
        val tiff = TiffBytes(bytes)
        val fields = tiff.firstIfd()

        val width = fields.requiredLong(TAG_IMAGE_WIDTH).toInt()
        val height = fields.requiredLong(TAG_IMAGE_LENGTH).toInt()
        val compression = fields.longValue(TAG_COMPRESSION, 1).toInt()
        require(compression == 1) { "暂不支持压缩 GeoTIFF（compression=$compression）" }

        val photometric = fields.longValue(TAG_PHOTOMETRIC, 2).toInt()
        require(photometric == 2) { "第一期仅支持 8-bit RGB GeoTIFF（photometric=$photometric）" }

        val samplesPerPixel = fields.longValue(TAG_SAMPLES_PER_PIXEL, 3).toInt()
        require(samplesPerPixel == 3 || samplesPerPixel == 4) {
            "第一期仅支持 RGB/RGBA GeoTIFF（samplesPerPixel=$samplesPerPixel）"
        }

        val parsedBits = fields.longArray(TAG_BITS_PER_SAMPLE)
        val bits = if (parsedBits.isEmpty()) longArrayOf(8, 8, 8) else parsedBits
        require(bits.take(samplesPerPixel).all { it == 8L }) {
            "第一期仅支持 8-bit RGB/RGBA GeoTIFF（bits=${bits.joinToString()}）"
        }

        val planar = fields.longValue(TAG_PLANAR_CONFIG, 1).toInt()
        require(planar == 1) { "第一期仅支持 chunky planar GeoTIFF（planar=$planar）" }

        require(!fields.contains(TAG_MODEL_TRANSFORMATION)) {
            "第一期暂不支持带 ModelTransformation 的旋转/仿射 GeoTIFF"
        }

        require(isWgs84(fields)) { "第一期仅支持 EPSG:4326 / WGS84 GeoTIFF" }

        val tiepoint = fields.doubleArray(TAG_MODEL_TIEPOINT)
        val pixelScale = fields.doubleArray(TAG_MODEL_PIXEL_SCALE)
        require(tiepoint.size >= 6 && pixelScale.size >= 2) {
            "GeoTIFF 缺少标准 ModelTiepoint / ModelPixelScale 地理参考信息"
        }

        val boundsWgs84 = GeoAffine.fromTiepointAndScale(tiepoint, pixelScale).boundsFor(width, height)
        val boundsGcj02 = CoordinateConverter.boundsWgs84ToGcj02(boundsWgs84)
        val preview = decodePreview(
            bytes = bytes,
            fields = fields,
            sourceWidth = width,
            sourceHeight = height,
            samplesPerPixel = samplesPerPixel,
            maxEdge = maxEdge,
        )
        return GeoRasterLoadResult(
            previewBitmap = preview,
            metadata = GeoMetadata(
                crs = "EPSG:4326",
                sourceWidth = width,
                sourceHeight = height,
                previewWidth = preview.width,
                previewHeight = preview.height,
                modelInputWidth = DEFAULT_SEGMENTATION_INPUT_SIZE,
                modelInputHeight = DEFAULT_SEGMENTATION_INPUT_SIZE,
                boundsWgs84 = boundsWgs84,
                boundsGcj02 = boundsGcj02,
            ),
        )
    }

    private fun decodePreview(
        bytes: ByteArray,
        fields: TiffFields,
        sourceWidth: Int,
        sourceHeight: Int,
        samplesPerPixel: Int,
        maxEdge: Int,
    ): Bitmap {
        val sample = previewSampleSize(sourceWidth, sourceHeight, maxEdge)
        val previewWidth = (sourceWidth + sample - 1) / sample
        val previewHeight = (sourceHeight + sample - 1) / sample
        val rowsPerStrip = fields.longValue(TAG_ROWS_PER_STRIP, sourceHeight.toLong()).toInt()
        val stripOffsets = fields.longArray(TAG_STRIP_OFFSETS)
        val stripByteCounts = fields.longArray(TAG_STRIP_BYTE_COUNTS)
        require(stripOffsets.isNotEmpty() && stripByteCounts.isNotEmpty()) {
            "GeoTIFF 缺少 strip offset / byte count"
        }
        val rowStride = sourceWidth * samplesPerPixel
        val pixels = IntArray(previewWidth * previewHeight)

        for (py in 0 until previewHeight) {
            val sy = (py * sample).coerceAtMost(sourceHeight - 1)
            val stripIndex = (sy / rowsPerStrip).coerceIn(stripOffsets.indices)
            val rowInStrip = sy - stripIndex * rowsPerStrip
            val stripStart = stripOffsets[stripIndex].toInt()
            val stripEnd = stripStart + stripByteCounts[stripIndex].toInt()
            val rowStart = stripStart + rowInStrip * rowStride
            require(rowStart + rowStride <= stripEnd && stripEnd <= bytes.size) {
                "GeoTIFF strip 数据长度不匹配"
            }
            for (px in 0 until previewWidth) {
                val sx = (px * sample).coerceAtMost(sourceWidth - 1)
                val idx = rowStart + sx * samplesPerPixel
                val r = bytes[idx].toInt() and 0xFF
                val g = bytes[idx + 1].toInt() and 0xFF
                val b = bytes[idx + 2].toInt() and 0xFF
                pixels[py * previewWidth + px] = Color.rgb(r, g, b)
            }
        }

        return Bitmap.createBitmap(previewWidth, previewHeight, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, previewWidth, 0, 0, previewWidth, previewHeight)
        }
    }

    private fun previewSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        var sample = 1
        val longest = max(width, height)
        while (longest / sample > maxEdge) {
            sample *= 2
        }
        return sample
    }

    private fun isWgs84(fields: TiffFields): Boolean {
        val geoKeys = fields.longArray(TAG_GEO_KEY_DIRECTORY)
        if (geoKeys.size < 4) return false
        val keyCount = geoKeys[3].toInt()
        for (i in 0 until keyCount) {
            val base = 4 + i * 4
            if (base + 3 >= geoKeys.size) break
            val keyId = geoKeys[base].toInt()
            val tiffTagLocation = geoKeys[base + 1].toInt()
            val valueOffset = geoKeys[base + 3].toInt()
            if (keyId == GEOGRAPHIC_TYPE_GEO_KEY && tiffTagLocation == 0 && valueOffset == EPSG_WGS84) {
                return true
            }
        }
        return false
    }

    private data class TiffField(
        val tag: Int,
        val type: Int,
        val count: Long,
        val valueOffset: Int,
    )

    private class TiffFields(
        private val source: TiffBytes,
        private val fields: Map<Int, TiffField>,
    ) {
        fun contains(tag: Int): Boolean = fields.containsKey(tag)

        fun requiredLong(tag: Int): Long = longArray(tag).firstOrNull()
            ?: error("GeoTIFF 缺少 tag $tag")

        fun longValue(tag: Int, default: Long): Long = longArray(tag).firstOrNull() ?: default

        fun longArray(tag: Int): LongArray {
            val field = fields[tag] ?: return LongArray(0)
            return when (field.type) {
                TYPE_BYTE -> source.readUnsignedBytes(field)
                TYPE_SHORT -> source.readShorts(field)
                TYPE_LONG -> source.readLongs(field)
                else -> LongArray(0)
            }
        }

        fun doubleArray(tag: Int): DoubleArray {
            val field = fields[tag] ?: return DoubleArray(0)
            return when (field.type) {
                TYPE_DOUBLE -> source.readDoubles(field)
                else -> DoubleArray(0)
            }
        }
    }

    private class TiffBytes(private val bytes: ByteArray) {
        private val order: ByteOrder
        private val firstIfdOffset: Int

        init {
            require(bytes.size >= 8) { "不是有效 TIFF 文件" }
            order = when {
                bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte() -> ByteOrder.LITTLE_ENDIAN
                bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte() -> ByteOrder.BIG_ENDIAN
                else -> error("不是有效 TIFF 字节序标记")
            }
            require(readUShort(2) == TIFF_MAGIC) { "不是有效 TIFF 文件" }
            firstIfdOffset = readInt(4)
        }

        fun firstIfd(): TiffFields {
            val count = readUShort(firstIfdOffset)
            val map = mutableMapOf<Int, TiffField>()
            for (i in 0 until count) {
                val entryOffset = firstIfdOffset + 2 + i * IFD_ENTRY_SIZE
                val tag = readUShort(entryOffset)
                val type = readUShort(entryOffset + 2)
                val valueCount = readInt(entryOffset + 4).toLong()
                val valueOffset = entryOffset + 8
                map[tag] = TiffField(tag, type, valueCount, valueOffset)
            }
            return TiffFields(this, map)
        }

        fun readUnsignedBytes(field: TiffField): LongArray {
            val offset = valueDataOffset(field)
            return LongArray(field.count.toInt()) { idx -> bytes[offset + idx].toLong() and 0xFFL }
        }

        fun readShorts(field: TiffField): LongArray {
            val offset = valueDataOffset(field)
            return LongArray(field.count.toInt()) { idx -> readUShort(offset + idx * 2).toLong() }
        }

        fun readLongs(field: TiffField): LongArray {
            val offset = valueDataOffset(field)
            return LongArray(field.count.toInt()) { idx -> readInt(offset + idx * 4).toLong() and 0xFFFFFFFFL }
        }

        fun readDoubles(field: TiffField): DoubleArray {
            val offset = valueDataOffset(field)
            return DoubleArray(field.count.toInt()) { idx -> java.lang.Double.longBitsToDouble(readLong64(offset + idx * 8)) }
        }

        private fun valueDataOffset(field: TiffField): Int {
            val byteCount = field.count * typeSize(field.type)
            return if (byteCount <= 4) field.valueOffset else readInt(field.valueOffset)
        }

        private fun readUShort(offset: Int): Int {
            require(offset + 2 <= bytes.size) { "TIFF offset 越界" }
            val b0 = bytes[offset].toInt() and 0xFF
            val b1 = bytes[offset + 1].toInt() and 0xFF
            return if (order == ByteOrder.LITTLE_ENDIAN) b0 or (b1 shl 8) else (b0 shl 8) or b1
        }

        private fun readInt(offset: Int): Int {
            require(offset + 4 <= bytes.size) { "TIFF offset 越界" }
            val b0 = bytes[offset].toInt() and 0xFF
            val b1 = bytes[offset + 1].toInt() and 0xFF
            val b2 = bytes[offset + 2].toInt() and 0xFF
            val b3 = bytes[offset + 3].toInt() and 0xFF
            return if (order == ByteOrder.LITTLE_ENDIAN) {
                b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            } else {
                (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
            }
        }

        private fun readLong64(offset: Int): Long {
            require(offset + 8 <= bytes.size) { "TIFF offset 越界" }
            var value = 0L
            if (order == ByteOrder.LITTLE_ENDIAN) {
                for (i in 7 downTo 0) {
                    value = (value shl 8) or (bytes[offset + i].toLong() and 0xFFL)
                }
            } else {
                for (i in 0 until 8) {
                    value = (value shl 8) or (bytes[offset + i].toLong() and 0xFFL)
                }
            }
            return value
        }
    }

    private fun typeSize(type: Int): Int = when (type) {
        TYPE_BYTE -> 1
        TYPE_SHORT -> 2
        TYPE_LONG -> 4
        TYPE_DOUBLE -> 8
        else -> error("不支持的 TIFF field type: $type")
    }

    private const val TIFF_MAGIC = 42
    private const val IFD_ENTRY_SIZE = 12
    private const val TYPE_BYTE = 1
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_DOUBLE = 12

    private const val TAG_IMAGE_WIDTH = 256
    private const val TAG_IMAGE_LENGTH = 257
    private const val TAG_BITS_PER_SAMPLE = 258
    private const val TAG_COMPRESSION = 259
    private const val TAG_PHOTOMETRIC = 262
    private const val TAG_STRIP_OFFSETS = 273
    private const val TAG_SAMPLES_PER_PIXEL = 277
    private const val TAG_ROWS_PER_STRIP = 278
    private const val TAG_STRIP_BYTE_COUNTS = 279
    private const val TAG_PLANAR_CONFIG = 284
    private const val TAG_MODEL_PIXEL_SCALE = 33550
    private const val TAG_MODEL_TIEPOINT = 33922
    private const val TAG_MODEL_TRANSFORMATION = 34264
    private const val TAG_GEO_KEY_DIRECTORY = 34735
    private const val GEOGRAPHIC_TYPE_GEO_KEY = 2048
    private const val EPSG_WGS84 = 4326
    private const val DEFAULT_SEGMENTATION_INPUT_SIZE = 512
}

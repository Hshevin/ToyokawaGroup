package com.example.skyedge.core.geo

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteOrder
import kotlin.math.max

/**
 * 8-bit RGB/RGBA TIFF 解码（无压缩 / LZW / Deflate），供检测页与 GeoTIFF 共用。
 */
internal object TiffImageDecoder {
    fun isTiff(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            ((bytes[0] == 'I'.code.toByte() && bytes[1] == 'I'.code.toByte()) ||
                (bytes[0] == 'M'.code.toByte() && bytes[1] == 'M'.code.toByte()))

    fun parse(bytes: ByteArray): ParsedTiff {
        require(isTiff(bytes)) { "不是有效 TIFF 文件" }
        val tiff = TiffBytes(bytes)
        val fields = tiff.firstIfd()

        val width = fields.requiredLong(TAG_IMAGE_WIDTH).toInt()
        val height = fields.requiredLong(TAG_IMAGE_LENGTH).toInt()
        val compression = fields.longValue(TAG_COMPRESSION, 1).toInt()
        require(compression in SUPPORTED_COMPRESSIONS) {
            "暂不支持该 TIFF 压缩方式（compression=$compression）"
        }
        val predictor = fields.longValue(TAG_PREDICTOR, 1).toInt()
        require(predictor in SUPPORTED_PREDICTORS) {
            "暂不支持该 TIFF predictor（predictor=$predictor）"
        }

        val photometric = fields.longValue(TAG_PHOTOMETRIC, 2).toInt()
        require(photometric == 2) { "仅支持 8-bit RGB TIFF（photometric=$photometric）" }

        val samplesPerPixel = fields.longValue(TAG_SAMPLES_PER_PIXEL, 3).toInt()
        require(samplesPerPixel == 3 || samplesPerPixel == 4) {
            "仅支持 RGB/RGBA TIFF（samplesPerPixel=$samplesPerPixel）"
        }

        val parsedBits = fields.longArray(TAG_BITS_PER_SAMPLE)
        val bits = if (parsedBits.isEmpty()) longArrayOf(8, 8, 8) else parsedBits
        require(bits.take(samplesPerPixel).all { it == 8L }) {
            "仅支持 8-bit RGB/RGBA TIFF（bits=${bits.joinToString()}）"
        }

        val planar = fields.longValue(TAG_PLANAR_CONFIG, 1).toInt()
        require(planar == 1) { "仅支持 chunky planar TIFF（planar=$planar）" }

        return ParsedTiff(
            fields = fields,
            width = width,
            height = height,
            samplesPerPixel = samplesPerPixel,
            compression = compression,
            predictor = predictor,
        )
    }

    fun decodeBitmap(bytes: ByteArray, maxEdge: Int = Int.MAX_VALUE): Bitmap {
        val parsed = parse(bytes)
        return decodeRaster(
            bytes = bytes,
            parsed = parsed,
            maxEdge = maxEdge,
        )
    }

    private fun decodeRaster(
        bytes: ByteArray,
        parsed: ParsedTiff,
        maxEdge: Int,
    ): Bitmap {
        val sample = previewSampleSize(parsed.width, parsed.height, maxEdge)
        val outputWidth = (parsed.width + sample - 1) / sample
        val outputHeight = (parsed.height + sample - 1) / sample
        val rowsPerStrip = parsed.fields.longValue(TAG_ROWS_PER_STRIP, parsed.height.toLong()).toInt()
        val stripOffsets = parsed.fields.longArray(TAG_STRIP_OFFSETS)
        val stripByteCounts = parsed.fields.longArray(TAG_STRIP_BYTE_COUNTS)
        require(stripOffsets.isNotEmpty() && stripByteCounts.isNotEmpty()) {
            "TIFF 缺少 strip offset / byte count"
        }

        val rowStride = parsed.width * parsed.samplesPerPixel
        val stripCache = mutableMapOf<Int, ByteArray>()
        val pixels = IntArray(outputWidth * outputHeight)

        for (py in 0 until outputHeight) {
            val sy = (py * sample).coerceAtMost(parsed.height - 1)
            val stripIndex = (sy / rowsPerStrip).coerceIn(stripOffsets.indices)
            val rowInStrip = sy - stripIndex * rowsPerStrip
            val stripBytes = stripCache.getOrPut(stripIndex) {
                decompressStrip(
                    bytes = bytes,
                    stripIndex = stripIndex,
                    stripOffsets = stripOffsets,
                    stripByteCounts = stripByteCounts,
                    rowsPerStrip = rowsPerStrip,
                    sourceHeight = parsed.height,
                    rowStride = rowStride,
                    samplesPerPixel = parsed.samplesPerPixel,
                    compression = parsed.compression,
                    predictor = parsed.predictor,
                )
            }
            val rowStart = rowInStrip * rowStride
            require(rowStart + rowStride <= stripBytes.size) {
                "TIFF strip 解压后数据长度不匹配"
            }
            for (px in 0 until outputWidth) {
                val sx = (px * sample).coerceAtMost(parsed.width - 1)
                val idx = rowStart + sx * parsed.samplesPerPixel
                val r = stripBytes[idx].toInt() and 0xFF
                val g = stripBytes[idx + 1].toInt() and 0xFF
                val b = stripBytes[idx + 2].toInt() and 0xFF
                pixels[py * outputWidth + px] = Color.rgb(r, g, b)
            }
        }

        return Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888).also {
            it.setPixels(pixels, 0, outputWidth, 0, 0, outputWidth, outputHeight)
        }
    }

    private fun decompressStrip(
        bytes: ByteArray,
        stripIndex: Int,
        stripOffsets: LongArray,
        stripByteCounts: LongArray,
        rowsPerStrip: Int,
        sourceHeight: Int,
        rowStride: Int,
        samplesPerPixel: Int,
        compression: Int,
        predictor: Int,
    ): ByteArray {
        val stripStart = stripOffsets[stripIndex].toInt()
        val stripSize = stripByteCounts[stripIndex].toInt()
        require(stripStart >= 0 && stripStart + stripSize <= bytes.size) {
            "TIFF strip offset 越界"
        }
        val rowsInStrip = minOf(rowsPerStrip, sourceHeight - stripIndex * rowsPerStrip)
        val expectedSize = rowsInStrip * rowStride
        val compressed = bytes.copyOfRange(stripStart, stripStart + stripSize)
        val raw = when (compression) {
            COMPRESSION_NONE -> compressed
            COMPRESSION_LZW -> TiffLzwDecompressor.decompress(compressed, expectedSize)
            COMPRESSION_DEFLATE -> TiffDeflateDecompressor.decompress(compressed, expectedSize)
            else -> error("不支持的 TIFF 压缩方式: $compression")
        }
        return if (predictor == PREDICTOR_HORIZONTAL) {
            TiffPredictor.decodeHorizontalDifference(raw, samplesPerPixel)
        } else {
            raw
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

    internal data class ParsedTiff(
        val fields: TiffFields,
        val width: Int,
        val height: Int,
        val samplesPerPixel: Int,
        val compression: Int,
        val predictor: Int,
    )

    internal data class TiffField(
        val tag: Int,
        val type: Int,
        val count: Long,
        val valueOffset: Int,
    )

    internal class TiffFields(
        private val source: TiffBytes,
        private val fields: Map<Int, TiffField>,
    ) {
        fun contains(tag: Int): Boolean = fields.containsKey(tag)

        fun requiredLong(tag: Int): Long = longArray(tag).firstOrNull()
            ?: error("TIFF 缺少 tag $tag")

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

    internal class TiffBytes(private val bytes: ByteArray) {
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
            return DoubleArray(field.count.toInt()) { idx ->
                java.lang.Double.longBitsToDouble(readLong64(offset + idx * 8))
            }
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

    const val TAG_IMAGE_WIDTH = 256
    const val TAG_IMAGE_LENGTH = 257
    const val TAG_BITS_PER_SAMPLE = 258
    const val TAG_COMPRESSION = 259
    const val TAG_PHOTOMETRIC = 262
    const val TAG_STRIP_OFFSETS = 273
    const val TAG_SAMPLES_PER_PIXEL = 277
    const val TAG_ROWS_PER_STRIP = 278
    const val TAG_STRIP_BYTE_COUNTS = 279
    const val TAG_PLANAR_CONFIG = 284
    const val TAG_PREDICTOR = 317
    const val TAG_MODEL_PIXEL_SCALE = 33550
    const val TAG_MODEL_TIEPOINT = 33922
    const val TAG_MODEL_TRANSFORMATION = 34264
    const val TAG_GEO_KEY_DIRECTORY = 34735
    const val GEOGRAPHIC_TYPE_GEO_KEY = 2048
    const val EPSG_WGS84 = 4326

    private const val TIFF_MAGIC = 42
    private const val IFD_ENTRY_SIZE = 12
    private const val TYPE_BYTE = 1
    private const val TYPE_SHORT = 3
    private const val TYPE_LONG = 4
    private const val TYPE_DOUBLE = 12

    private const val COMPRESSION_NONE = 1
    private const val COMPRESSION_LZW = 5
    private const val COMPRESSION_DEFLATE = 8
    private val SUPPORTED_COMPRESSIONS = setOf(COMPRESSION_NONE, COMPRESSION_LZW, COMPRESSION_DEFLATE)

    private const val PREDICTOR_NONE = 1
    private const val PREDICTOR_HORIZONTAL = 2
    private val SUPPORTED_PREDICTORS = setOf(PREDICTOR_NONE, PREDICTOR_HORIZONTAL)
}

package com.example.skyedge.core.geo

import java.util.zip.DataFormatException
import java.util.zip.Inflater

/**
 * TIFF / GeoTIFF Deflate (compression=8) strip decompressor.
 */
internal object TiffDeflateDecompressor {
    fun decompress(data: ByteArray, expectedSize: Int): ByteArray {
        val inflater = Inflater()
        return try {
            inflater.setInput(data)
            val output = ByteArray(expectedSize)
            val written = inflater.inflate(output)
            require(written == expectedSize) {
                "Deflate 解压长度不匹配: got=$written expected=$expectedSize"
            }
            output
        } catch (error: DataFormatException) {
            error("Deflate 解压失败: ${error.message}")
        } finally {
            inflater.end()
        }
    }
}

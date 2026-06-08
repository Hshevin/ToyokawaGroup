package com.example.skyedge.core.geo

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri

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
        val parsed = TiffImageDecoder.parse(bytes)

        require(!parsed.fields.contains(TiffImageDecoder.TAG_MODEL_TRANSFORMATION)) {
            "第一期暂不支持带 ModelTransformation 的旋转/仿射 GeoTIFF"
        }
        require(isWgs84(parsed.fields)) { "第一期仅支持 EPSG:4326 / WGS84 GeoTIFF" }

        val tiepoint = parsed.fields.doubleArray(TiffImageDecoder.TAG_MODEL_TIEPOINT)
        val pixelScale = parsed.fields.doubleArray(TiffImageDecoder.TAG_MODEL_PIXEL_SCALE)
        require(tiepoint.size >= 6 && pixelScale.size >= 2) {
            "GeoTIFF 缺少标准 ModelTiepoint / ModelPixelScale 地理参考信息"
        }

        val boundsWgs84 = GeoAffine.fromTiepointAndScale(tiepoint, pixelScale)
            .boundsFor(parsed.width, parsed.height)
        val boundsGcj02 = CoordinateConverter.boundsWgs84ToGcj02(boundsWgs84)
        val preview = TiffImageDecoder.decodeBitmap(bytes, maxEdge)
        return GeoRasterLoadResult(
            previewBitmap = preview,
            metadata = GeoMetadata(
                crs = "EPSG:4326",
                sourceWidth = parsed.width,
                sourceHeight = parsed.height,
                previewWidth = preview.width,
                previewHeight = preview.height,
                modelInputWidth = DEFAULT_SEGMENTATION_INPUT_SIZE,
                modelInputHeight = DEFAULT_SEGMENTATION_INPUT_SIZE,
                boundsWgs84 = boundsWgs84,
                boundsGcj02 = boundsGcj02,
            ),
        )
    }

    private fun isWgs84(fields: TiffImageDecoder.TiffFields): Boolean {
        val geoKeys = fields.longArray(TiffImageDecoder.TAG_GEO_KEY_DIRECTORY)
        if (geoKeys.size < 4) return false
        val keyCount = geoKeys[3].toInt()
        for (i in 0 until keyCount) {
            val base = 4 + i * 4
            if (base + 3 >= geoKeys.size) break
            val keyId = geoKeys[base].toInt()
            val tiffTagLocation = geoKeys[base + 1].toInt()
            val valueOffset = geoKeys[base + 3].toInt()
            if (
                keyId == TiffImageDecoder.GEOGRAPHIC_TYPE_GEO_KEY &&
                tiffTagLocation == 0 &&
                valueOffset == TiffImageDecoder.EPSG_WGS84
            ) {
                return true
            }
        }
        return false
    }

    private const val DEFAULT_SEGMENTATION_INPUT_SIZE = 512
}

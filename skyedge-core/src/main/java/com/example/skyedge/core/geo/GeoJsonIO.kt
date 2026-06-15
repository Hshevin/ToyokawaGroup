package com.example.skyedge.core.geo

import java.io.File
import org.json.JSONObject

object GeoJsonIO {
    const val GEO_JSON = "geo.json"

    fun geoFile(outputDir: File): File = File(outputDir, GEO_JSON)

    fun write(metadata: GeoMetadata, outputFile: File): String {
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(toJson(metadata).toString())
        return outputFile.absolutePath
    }

    fun read(file: File): GeoMetadata? =
        runCatching { fromJson(JSONObject(file.readText())) }.getOrNull()

    fun toJson(metadata: GeoMetadata): JSONObject =
        JSONObject()
            .put("crs", metadata.crs)
            .put("source_width", metadata.sourceWidth)
            .put("source_height", metadata.sourceHeight)
            .put("preview_width", metadata.previewWidth)
            .put("preview_height", metadata.previewHeight)
            .put("model_input_width", metadata.modelInputWidth)
            .put("model_input_height", metadata.modelInputHeight)
            .put("bounds_wgs84", boundsToJson(metadata.boundsWgs84))
            .put("bounds_gcj02", boundsToJson(metadata.boundsGcj02))
            .apply {
                metadata.geoAffine?.let { put("geo_affine", affineToJson(it)) }
                metadata.orthoPreviewPath?.let { put("ortho_preview_path", it) }
                metadata.maskOverlayPath?.let { put("mask_overlay_path", it) }
            }

    fun fromJson(json: JSONObject): GeoMetadata {
        val boundsWgs84 = boundsFromJson(json.getJSONObject("bounds_wgs84"))
        val sourceWidth = json.getInt("source_width")
        val sourceHeight = json.getInt("source_height")
        return GeoMetadata(
            crs = json.getString("crs"),
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            previewWidth = json.getInt("preview_width"),
            previewHeight = json.getInt("preview_height"),
            modelInputWidth = json.optInt("model_input_width", 512),
            modelInputHeight = json.optInt("model_input_height", 512),
            boundsWgs84 = boundsWgs84,
            boundsGcj02 = boundsFromJson(json.getJSONObject("bounds_gcj02")),
            geoAffine = json.optJSONObject("geo_affine")?.let { affineFromJson(it) },
            orthoPreviewPath = json.optString("ortho_preview_path").takeIf { it.isNotBlank() },
            maskOverlayPath = json.optString("mask_overlay_path").takeIf { it.isNotBlank() },
        )
    }

    private fun affineToJson(affine: GeoAffine): JSONObject =
        JSONObject()
            .put("origin_pixel_x", affine.originPixelX)
            .put("origin_pixel_y", affine.originPixelY)
            .put("origin_longitude", affine.originLongitude)
            .put("origin_latitude", affine.originLatitude)
            .put("scale_x", affine.scaleX)
            .put("scale_y", affine.scaleY)

    private fun affineFromJson(json: JSONObject): GeoAffine =
        GeoAffine(
            originPixelX = json.getDouble("origin_pixel_x"),
            originPixelY = json.getDouble("origin_pixel_y"),
            originLongitude = json.getDouble("origin_longitude"),
            originLatitude = json.getDouble("origin_latitude"),
            scaleX = json.getDouble("scale_x"),
            scaleY = json.getDouble("scale_y"),
        )

    fun boundsToJson(bounds: GeoBounds): JSONObject =
        JSONObject()
            .put("sw", latLngToJson(bounds.sw))
            .put("ne", latLngToJson(bounds.ne))

    fun boundsFromJson(json: JSONObject): GeoBounds =
        GeoBounds(
            sw = latLngFromJson(json.getJSONObject("sw")),
            ne = latLngFromJson(json.getJSONObject("ne")),
        )

    private fun latLngToJson(point: GeoLatLng): JSONObject =
        JSONObject()
            .put("lat", point.latitude)
            .put("lng", point.longitude)

    private fun latLngFromJson(json: JSONObject): GeoLatLng =
        GeoLatLng(
            latitude = json.getDouble("lat"),
            longitude = json.getDouble("lng"),
        )
}

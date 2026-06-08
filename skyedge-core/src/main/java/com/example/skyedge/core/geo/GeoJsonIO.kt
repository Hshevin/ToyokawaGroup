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
                metadata.orthoPreviewPath?.let { put("ortho_preview_path", it) }
                metadata.maskOverlayPath?.let { put("mask_overlay_path", it) }
            }

    fun fromJson(json: JSONObject): GeoMetadata =
        GeoMetadata(
            crs = json.getString("crs"),
            sourceWidth = json.getInt("source_width"),
            sourceHeight = json.getInt("source_height"),
            previewWidth = json.getInt("preview_width"),
            previewHeight = json.getInt("preview_height"),
            modelInputWidth = json.optInt("model_input_width", 512),
            modelInputHeight = json.optInt("model_input_height", 512),
            boundsWgs84 = boundsFromJson(json.getJSONObject("bounds_wgs84")),
            boundsGcj02 = boundsFromJson(json.getJSONObject("bounds_gcj02")),
            orthoPreviewPath = json.optString("ortho_preview_path").takeIf { it.isNotBlank() },
            maskOverlayPath = json.optString("mask_overlay_path").takeIf { it.isNotBlank() },
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

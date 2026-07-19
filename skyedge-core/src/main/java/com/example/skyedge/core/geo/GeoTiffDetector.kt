package com.example.skyedge.core.geo

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Detect whether an imported file should take the GeoTIFF map-overlay path.
 * Matches the frontend prototype: extension / mime decides geotiff vs plain image.
 */
object GeoTiffDetector {

    private val EXT = Regex("""\.(tif|tiff|geotiff)$""", RegexOption.IGNORE_CASE)

    fun isGeoTiffFileName(name: String?): Boolean =
        !name.isNullOrBlank() && EXT.containsMatchIn(name)

    fun isGeoTiffMime(mime: String?): Boolean {
        val m = mime?.lowercase() ?: return false
        return m == "image/tiff" || m == "image/x-tiff" || m == "image/geotiff"
    }

    fun isGeoTiff(context: Context, uri: Uri): Boolean {
        val displayName = queryDisplayName(context, uri)
        if (isGeoTiffFileName(displayName)) return true
        if (isGeoTiffFileName(uri.lastPathSegment)) return true
        return isGeoTiffMime(context.contentResolver.getType(uri))
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
            }
        }.getOrNull()
}

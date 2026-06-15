package com.example.skyedge.core.geo

import com.example.skyedge.core.api.BoundingBoxDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoAnomalyLocationResolverTest {

    @Test
    fun resolve_usesGeoAffineForSourcePixelCenter() {
        val metadata = GeoMetadata(
            crs = "EPSG:4326",
            sourceWidth = 2,
            sourceHeight = 2,
            previewWidth = 2,
            previewHeight = 2,
            modelInputWidth = 512,
            modelInputHeight = 512,
            boundsWgs84 = GeoBounds(
                sw = GeoLatLng(latitude = 29.8, longitude = 120.0),
                ne = GeoLatLng(latitude = 30.0, longitude = 120.2),
            ),
            boundsGcj02 = GeoBounds(
                sw = GeoLatLng(latitude = 29.8, longitude = 120.0),
                ne = GeoLatLng(latitude = 30.0, longitude = 120.2),
            ),
            geoAffine = GeoAffine(
                originPixelX = 0.0,
                originPixelY = 0.0,
                originLongitude = 120.0,
                originLatitude = 30.0,
                scaleX = 0.1,
                scaleY = 0.1,
            ),
        )
        val bbox = BoundingBoxDto(x = 0f, y = 0f, width = 1f, height = 1f)

        val location = GeoAnomalyLocationResolver.resolve(metadata, bbox, maskWidth = 512, maskHeight = 512)

        assertTrue(location.startsWith("GCJ-02 "))
        assertTrue(location.contains("°N"))
        assertTrue(location.contains("°E"))
    }

    @Test
    fun resolve_fallsBackToBoundsWhenAffineMissing() {
        val metadata = GeoMetadata(
            crs = "EPSG:4326",
            sourceWidth = 100,
            sourceHeight = 100,
            previewWidth = 50,
            previewHeight = 50,
            modelInputWidth = 512,
            modelInputHeight = 512,
            boundsWgs84 = GeoBounds(
                sw = GeoLatLng(latitude = 10.0, longitude = 20.0),
                ne = GeoLatLng(latitude = 20.0, longitude = 30.0),
            ),
            boundsGcj02 = GeoBounds(
                sw = GeoLatLng(latitude = 10.0, longitude = 20.0),
                ne = GeoLatLng(latitude = 20.0, longitude = 30.0),
            ),
            geoAffine = null,
        )
        val center = BoundingBoxDto(x = 0.25f, y = 0.25f, width = 0.5f, height = 0.5f)

        val location = GeoAnomalyLocationResolver.resolve(metadata, center, maskWidth = 512, maskHeight = 512)

        assertTrue(location.startsWith("WGS84 "))
        assertTrue(location.contains("15.000000°N"))
        assertTrue(location.contains("25.000000°E"))
    }

    @Test
    fun sourcePixelForBboxCenter_mapsThroughPreviewToSource() {
        val metadata = GeoMetadata(
            crs = "EPSG:4326",
            sourceWidth = 200,
            sourceHeight = 100,
            previewWidth = 100,
            previewHeight = 50,
            modelInputWidth = 512,
            modelInputHeight = 512,
            boundsWgs84 = GeoBounds(
                sw = GeoLatLng(0.0, 0.0),
                ne = GeoLatLng(1.0, 1.0),
            ),
            boundsGcj02 = GeoBounds(
                sw = GeoLatLng(0.0, 0.0),
                ne = GeoLatLng(1.0, 1.0),
            ),
            geoAffine = GeoAffine(0.0, 0.0, 0.0, 1.0, 0.01, 0.01),
        )
        val bbox = BoundingBoxDto(x = 0f, y = 0f, width = 1f, height = 1f)

        val (sourceX, sourceY) = GeoAnomalyLocationResolver.sourcePixelForBboxCenter(
            metadata,
            bbox,
            maskWidth = 512,
            maskHeight = 512,
        )

        assertEquals(100.0, sourceX, 0.001)
        assertEquals(50.0, sourceY, 0.001)
    }
}

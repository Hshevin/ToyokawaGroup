package com.example.skyedge.core.geo

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTiffDetectorTest {

    @Test
    fun fileName_detectsTiffExtensions() {
        assertTrue(GeoTiffDetector.isGeoTiffFileName("aoi.tif"))
        assertTrue(GeoTiffDetector.isGeoTiffFileName("AOI.TIFF"))
        assertTrue(GeoTiffDetector.isGeoTiffFileName("map.geotiff"))
        assertFalse(GeoTiffDetector.isGeoTiffFileName("photo.jpg"))
        assertFalse(GeoTiffDetector.isGeoTiffFileName("photo.png"))
        assertFalse(GeoTiffDetector.isGeoTiffFileName(null))
    }

    @Test
    fun mime_detectsTiffTypes() {
        assertTrue(GeoTiffDetector.isGeoTiffMime("image/tiff"))
        assertTrue(GeoTiffDetector.isGeoTiffMime("image/x-tiff"))
        assertFalse(GeoTiffDetector.isGeoTiffMime("image/jpeg"))
        assertFalse(GeoTiffDetector.isGeoTiffMime(null))
    }
}

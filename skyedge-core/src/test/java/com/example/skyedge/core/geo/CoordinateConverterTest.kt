package com.example.skyedge.core.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class CoordinateConverterTest {
    @Test
    fun wgs84ToGcj02_keepsOverseasCoordinates() {
        val tokyo = GeoLatLng(latitude = 35.681236, longitude = 139.767125)

        val converted = CoordinateConverter.wgs84ToGcj02(tokyo)

        assertEquals(tokyo.latitude, converted.latitude, 0.0)
        assertEquals(tokyo.longitude, converted.longitude, 0.0)
    }

    @Test
    fun wgs84ToGcj02_offsetsMainlandCoordinates() {
        val beijing = GeoLatLng(latitude = 39.908823, longitude = 116.39747)

        val converted = CoordinateConverter.wgs84ToGcj02(beijing)

        assertEquals(39.910226, converted.latitude, 0.001)
        assertEquals(116.403714, converted.longitude, 0.001)
    }
}

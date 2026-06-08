package com.example.skyedge.core.geo

import kotlin.math.cos
import kotlin.math.sin

data class GeoAffine(
    val originPixelX: Double,
    val originPixelY: Double,
    val originLongitude: Double,
    val originLatitude: Double,
    val scaleX: Double,
    val scaleY: Double,
) {
    fun pointForPixel(pixelX: Double, pixelY: Double): GeoLatLng {
        val longitude = originLongitude + (pixelX - originPixelX) * scaleX
        val latitude = originLatitude - (pixelY - originPixelY) * scaleY
        return GeoLatLng(latitude = latitude, longitude = longitude)
    }

    fun boundsFor(width: Int, height: Int): GeoBounds {
        val corners = listOf(
            pointForPixel(0.0, 0.0),
            pointForPixel(width.toDouble(), 0.0),
            pointForPixel(width.toDouble(), height.toDouble()),
            pointForPixel(0.0, height.toDouble()),
        )
        return GeoBounds.envelopeFromCorners(corners)
    }

    companion object {
        fun fromTiepointAndScale(tiepoint: DoubleArray, pixelScale: DoubleArray): GeoAffine {
            require(tiepoint.size >= 6) { "ModelTiepoint must contain at least 6 doubles" }
            require(pixelScale.size >= 2) { "ModelPixelScale must contain at least 2 doubles" }
            return GeoAffine(
                originPixelX = tiepoint[0],
                originPixelY = tiepoint[1],
                originLongitude = tiepoint[3],
                originLatitude = tiepoint[4],
                scaleX = pixelScale[0],
                scaleY = pixelScale[1],
            )
        }
    }
}

object CoordinateConverter {
    fun boundsWgs84ToGcj02(bounds: GeoBounds): GeoBounds {
        val corners = listOf(
            bounds.sw,
            GeoLatLng(latitude = bounds.sw.latitude, longitude = bounds.ne.longitude),
            bounds.ne,
            GeoLatLng(latitude = bounds.ne.latitude, longitude = bounds.sw.longitude),
        ).map { wgs84ToGcj02(it) }
        return GeoBounds.envelopeFromCorners(corners)
    }

    fun wgs84ToGcj02(point: GeoLatLng): GeoLatLng {
        if (outOfChina(point.latitude, point.longitude)) return point
        var dLat = transformLat(point.longitude - 105.0, point.latitude - 35.0)
        var dLng = transformLng(point.longitude - 105.0, point.latitude - 35.0)
        val radLat = point.latitude / 180.0 * PI
        var magic = sin(radLat)
        magic = 1 - EE * magic * magic
        val sqrtMagic = kotlin.math.sqrt(magic)
        dLat = (dLat * 180.0) / ((A * (1 - EE)) / (magic * sqrtMagic) * PI)
        dLng = (dLng * 180.0) / (A / sqrtMagic * cos(radLat) * PI)
        return GeoLatLng(
            latitude = point.latitude + dLat,
            longitude = point.longitude + dLng,
        )
    }

    private fun outOfChina(lat: Double, lng: Double): Boolean =
        lng < 72.004 || lng > 137.8347 || lat < 0.8293 || lat > 55.8271

    private fun transformLat(x: Double, y: Double): Double {
        var ret = -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y +
            0.2 * kotlin.math.sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(y * PI) + 40.0 * sin(y / 3.0 * PI)) * 2.0 / 3.0
        ret += (160.0 * sin(y / 12.0 * PI) + 320 * sin(y * PI / 30.0)) * 2.0 / 3.0
        return ret
    }

    private fun transformLng(x: Double, y: Double): Double {
        var ret = 300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y +
            0.1 * kotlin.math.sqrt(kotlin.math.abs(x))
        ret += (20.0 * sin(6.0 * x * PI) + 20.0 * sin(2.0 * x * PI)) * 2.0 / 3.0
        ret += (20.0 * sin(x * PI) + 40.0 * sin(x / 3.0 * PI)) * 2.0 / 3.0
        ret += (150.0 * sin(x / 12.0 * PI) + 300.0 * sin(x / 30.0 * PI)) * 2.0 / 3.0
        return ret
    }

    private const val PI = 3.14159265358979324
    private const val A = 6378245.0
    private const val EE = 0.00669342162296594323
}

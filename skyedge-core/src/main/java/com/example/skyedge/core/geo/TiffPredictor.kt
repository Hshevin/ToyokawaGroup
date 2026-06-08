package com.example.skyedge.core.geo

internal object TiffPredictor {
    fun decodeHorizontalDifference(data: ByteArray, samplesPerPixel: Int): ByteArray {
        if (data.isEmpty()) return data
        val output = data.copyOf()
        for (index in 1 until output.size) {
            output[index] = (output[index] + output[index - 1]).toByte()
        }
        return output
    }
}

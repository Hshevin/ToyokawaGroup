package com.example.skyedge.core.model

import android.graphics.Bitmap
import com.example.skyedge.core.geo.TiffImageDecoder

internal object TiffBitmapLoader {
    fun decode(bytes: ByteArray): Bitmap? {
        if (!TiffImageDecoder.isTiff(bytes)) return null
        return runCatching { TiffImageDecoder.decodeBitmap(bytes) }.getOrNull()
    }
}

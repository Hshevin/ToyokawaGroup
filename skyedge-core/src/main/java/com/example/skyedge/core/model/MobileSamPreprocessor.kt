package com.example.skyedge.core.model

import android.graphics.Bitmap
import org.pytorch.Tensor
import kotlin.math.roundToInt

object MobileSamPreprocessor {
    data class PreparedImage(
        val paddedTensor: Tensor,
        val origWidth: Int,
        val origHeight: Int,
        val resizedWidth: Int,
        val resizedHeight: Int,
    )

    fun prepare(bitmap: Bitmap, spec: LoadedModelSpec): PreparedImage {
        val encoderSize = spec.inputWidth.coerceAtLeast(spec.inputHeight)
        val origWidth = bitmap.width
        val origHeight = bitmap.height
        val scale = encoderSize.toFloat() / maxOf(origWidth, origHeight).toFloat()
        val resizedWidth = (origWidth * scale).roundToInt().coerceAtLeast(1)
        val resizedHeight = (origHeight * scale).roundToInt().coerceAtLeast(1)
        val resized = if (resizedWidth == origWidth && resizedHeight == origHeight) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, resizedWidth, resizedHeight, true)
        }

        val planeSize = encoderSize * encoderSize
        val data = FloatArray(3 * planeSize)
        val pixels = IntArray(resizedWidth * resizedHeight)
        resized.getPixels(pixels, 0, resizedWidth, 0, 0, resizedWidth, resizedHeight)
        if (resized !== bitmap) {
            resized.recycle()
        }

        for (y in 0 until resizedHeight) {
            for (x in 0 until resizedWidth) {
                val pixel = pixels[y * resizedWidth + x]
                val r = ((pixel shr 16) and 0xFF) / 255f
                val g = ((pixel shr 8) and 0xFF) / 255f
                val b = (pixel and 0xFF) / 255f
                val outIndex = y * encoderSize + x
                data[outIndex] = (r - spec.meanRgb[0]) / spec.stdRgb[0]
                data[planeSize + outIndex] = (g - spec.meanRgb[1]) / spec.stdRgb[1]
                data[2 * planeSize + outIndex] = (b - spec.meanRgb[2]) / spec.stdRgb[2]
            }
        }

        val tensor = Tensor.fromBlob(
            data,
            longArrayOf(1, 3, encoderSize.toLong(), encoderSize.toLong()),
        )
        return PreparedImage(
            paddedTensor = tensor,
            origWidth = origWidth,
            origHeight = origHeight,
            resizedWidth = resizedWidth,
            resizedHeight = resizedHeight,
        )
    }

    fun int64Scalar(value: Int): Tensor =
        Tensor.fromBlob(longArrayOf(value.toLong()), longArrayOf())

    fun pointTensor(x: Float, y: Float): Tensor =
        Tensor.fromBlob(
            floatArrayOf(x, y),
            longArrayOf(1, 1, 2),
        )

    fun labelTensor(label: Int = 1): Tensor =
        Tensor.fromBlob(longArrayOf(label.toLong()), longArrayOf(1, 1))
}

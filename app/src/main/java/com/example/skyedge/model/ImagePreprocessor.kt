package com.example.skyedge.model

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils

object ImagePreprocessor {
    fun loadOrientedBitmap(context: Context, uri: Uri): Bitmap? {
        val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream)
        } ?: return null

        val rotation = context.contentResolver.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f

        if (rotation == 0f) {
            return decoded
        }
        val matrix = Matrix().apply { postRotate(rotation) }
        return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also {
            if (it !== decoded) {
                decoded.recycle()
            }
        }
    }

    fun resizeToModelInput(source: Bitmap, spec: LoadedModelSpec): Bitmap {
        if (source.width == spec.inputWidth && source.height == spec.inputHeight) {
            return source
        }
        return Bitmap.createScaledBitmap(
            source,
            spec.inputWidth,
            spec.inputHeight,
            true,
        )
    }

    fun bitmapToInputTensor(bitmap: Bitmap, spec: LoadedModelSpec): Tensor {
        val resized = resizeToModelInput(bitmap, spec)
        val tensor = TensorImageUtils.bitmapToFloat32Tensor(
            resized,
            spec.meanRgb,
            spec.stdRgb,
        )
        if (resized !== bitmap) {
            resized.recycle()
        }
        return tensor
    }
}

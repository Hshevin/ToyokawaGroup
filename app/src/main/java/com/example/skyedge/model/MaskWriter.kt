package com.example.skyedge.model

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

object MaskWriter {
    fun inspectionMaskFile(baseDir: File, localId: String): File {
        val dir = File(baseDir, "${ModelSpec.INSPECTIONS_DIR}/$localId")
        dir.mkdirs()
        return File(dir, "mask.png")
    }

    fun writeClassIndices(
        classIndices: IntArray,
        width: Int,
        height: Int,
        outputFile: File,
    ): String {
        require(classIndices.size == width * height) {
            "classIndices size ${classIndices.size} != ${width}x$height"
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        for (i in classIndices.indices) {
            val c = classIndices[i].coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (c shl 16) or (c shl 8) or c
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return outputFile.absolutePath
    }
}

package com.example.skyedge.model

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

object MaskWriter {
    fun inspectionDir(baseDir: File, localId: String): File =
        File(baseDir, "${ModelSpec.ANALYSIS_DIR}/$localId").apply { mkdirs() }

    fun inspectionMaskFile(baseDir: File, localId: String): File =
        maskFile(inspectionDir(baseDir, localId))

    fun maskFile(outputDir: File): File = File(outputDir, "mask.png")

    fun maskFileForLocalUrl(localUrl: String): File {
        val dir = File(localUrl.trimEnd('/', '\\'))
        dir.mkdirs()
        return maskFile(dir)
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

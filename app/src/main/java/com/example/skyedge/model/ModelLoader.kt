package com.example.skyedge.model

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object ModelLoader {
    fun assetExists(context: Context, assetName: String): Boolean =
        runCatching {
            context.assets.open(assetName).close()
            true
        }.getOrDefault(false)

    fun assetFilePath(context: Context, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }
        file.parentFile?.mkdirs()
        context.assets.open(assetName).use { input ->
            FileOutputStream(file).use { output ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
        return file.absolutePath
    }
}

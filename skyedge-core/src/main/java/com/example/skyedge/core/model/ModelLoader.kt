package com.example.skyedge.core.model

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object ModelLoader {
    fun assetExists(context: Context, assetName: String): Boolean =
        runCatching {
            context.assets.open(assetName).close()
            true
        }.getOrDefault(false)

    fun resolveModelAsset(context: Context, assetName: String, cacheToken: String): String {
        if (assetName.endsWith(".fp8pkg")) {
            val runtimeAsset = assetName.removeSuffix(".fp8pkg") + "_runtime.pt"
            check(assetExists(context, runtimeAsset)) {
                "FP8 包 $assetName 需要配套运行时模型 $runtimeAsset"
            }
            return assetFilePath(context, runtimeAsset, cacheToken)
        }
        return assetFilePath(context, assetName, cacheToken)
    }

    fun assetFilePath(context: Context, assetName: String, cacheToken: String = assetName): String {
        val file = File(context.filesDir, assetName)
        val marker = cacheMarkerFile(context.filesDir, assetName)
        val cacheHit = file.exists() &&
            file.length() > 0 &&
            marker.exists() &&
            marker.readText() == cacheToken
        if (cacheHit) {
            return file.absolutePath
        }
        if (file.exists()) {
            file.delete()
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
        marker.parentFile?.mkdirs()
        marker.writeText(cacheToken)
        return file.absolutePath
    }

    fun clearModelCache(filesDir: File) {
        filesDir.listFiles()?.forEach { entry ->
            if (entry.isFile && entry.name.endsWith(".cache_token")) {
                entry.delete()
            }
        }
        val optimized = File(filesDir, "optimized")
        if (optimized.exists()) {
            optimized.deleteRecursively()
        }
    }

    private fun cacheMarkerFile(filesDir: File, assetName: String): File =
        File(filesDir, assetName.replace('/', '_') + ".cache_token")
}

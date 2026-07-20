package com.example.skyedge.core.model

import android.content.Context
import java.io.File
import java.io.FileOutputStream

object ModelLoader {
    /** Resolve model paths relative to the directory containing model_spec.json. */
    fun resolveAssetPath(specAsset: String?, assetPath: String): String {
        if (assetPath.startsWith("models/")) return assetPath
        val base = specAsset?.substringBeforeLast('/')?.takeIf { it.isNotEmpty() } ?: return assetPath
        return "$base/$assetPath"
    }

    fun assetExists(context: Context, assetName: String): Boolean =
        runCatching {
            context.assets.open(assetName).close()
            true
        }.getOrDefault(false)

    fun assetLength(context: Context, assetName: String): Long =
        runCatching { context.assets.openFd(assetName).use { it.length } }
            .getOrElse {
                context.assets.open(assetName).use { stream ->
                    var total = 0L
                    val buffer = ByteArray(8 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read <= 0) break
                        total += read
                    }
                    total
                }
            }

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

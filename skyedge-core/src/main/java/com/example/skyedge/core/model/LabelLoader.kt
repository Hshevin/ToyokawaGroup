package com.example.skyedge.core.model

import android.content.Context

object LabelLoader {
    fun load(context: Context, assetName: String = ModelSpec.LABELS_ASSET): List<String>? {
        if (!ModelLoader.assetExists(context, assetName)) {
            return null
        }
        return context.assets.open(assetName).bufferedReader().useLines { lines ->
            lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
        }.takeIf { it.isNotEmpty() }
    }
}

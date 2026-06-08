package com.example.skyedge.core.model

import android.content.Context
import org.json.JSONObject

enum class TaskType {
    CLASSIFICATION,
    SEGMENTATION,
}

data class LoadedModelSpec(
    val modelId: String,
    val assetFile: String?,
    val quantizationRuntimeAsset: String?,
    val taskType: TaskType,
    val inputHeight: Int,
    val inputWidth: Int,
    val meanRgb: FloatArray,
    val stdRgb: FloatArray,
    val numClasses: Int,
    val postprocess: String,
    val threshold: Float?,
    val classNames: List<String>,
)

object ModelSpecLoader {
    private const val SPEC_ASSET = "model_spec.json"

    fun load(context: Context, specAsset: String? = null): LoadedModelSpec {
        val targetAsset = specAsset ?: SPEC_ASSET
        if (!ModelLoader.assetExists(context, targetAsset)) {
            return defaultSpec()
        }
        return context.assets.open(targetAsset).bufferedReader().use { reader ->
            parse(JSONObject(stripBom(reader.readText())))
        }
    }

    private fun stripBom(text: String): String =
        if (text.isNotEmpty() && text[0] == '\uFEFF') text.substring(1) else text

    private fun parse(json: JSONObject): LoadedModelSpec {
        val input = json.getJSONObject("input")
        val output = json.getJSONObject("output")
        val mean = input.getJSONArray("mean")
        val std = input.getJSONArray("std")
        val names = json.optJSONArray("class_names")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        val task = when (json.optString("task_type", "segmentation").lowercase()) {
            "classification" -> TaskType.CLASSIFICATION
            else -> TaskType.SEGMENTATION
        }

        return LoadedModelSpec(
            modelId = json.getString("model_id"),
            assetFile = json.optString("asset_file").takeIf { it.isNotEmpty() },
            quantizationRuntimeAsset = json.optJSONObject("quantization")
                ?.optString("runtime_asset")
                ?.takeIf { it.isNotEmpty() },
            taskType = task,
            inputHeight = input.getInt("height"),
            inputWidth = input.getInt("width"),
            meanRgb = floatArrayOf(
                mean.getDouble(0).toFloat(),
                mean.getDouble(1).toFloat(),
                mean.getDouble(2).toFloat(),
            ),
            stdRgb = floatArrayOf(
                std.getDouble(0).toFloat(),
                std.getDouble(1).toFloat(),
                std.getDouble(2).toFloat(),
            ),
            numClasses = json.getInt("num_classes"),
            postprocess = output.getString("postprocess"),
            threshold = if (output.isNull("threshold")) null else output.getDouble("threshold").toFloat(),
            classNames = names,
        )
    }

    fun defaultSpec(): LoadedModelSpec = LoadedModelSpec(
        modelId = "placeholder_classification",
        assetFile = null,
        quantizationRuntimeAsset = null,
        taskType = TaskType.CLASSIFICATION,
        inputHeight = ModelSpec.INPUT_HEIGHT,
        inputWidth = ModelSpec.INPUT_WIDTH,
        meanRgb = ModelSpec.MEAN_RGB,
        stdRgb = ModelSpec.STD_RGB,
        numClasses = 1000,
        postprocess = "argmax",
        threshold = null,
        classNames = emptyList(),
    )
}

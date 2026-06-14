package com.example.skyedge.core.model

import android.content.Context
import org.json.JSONObject

enum class TaskType {
    CLASSIFICATION,
    SEGMENTATION,
    INTERACTIVE_SEGMENTATION,
}

data class LoadedModelSpec(
    val modelId: String,
    val assetFile: String?,
    val decoderAssetFile: String?,
    val quantizationRuntimeAsset: String?,
    val decoderQuantizationRuntimeAsset: String?,
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
            "interactive_segmentation" -> TaskType.INTERACTIVE_SEGMENTATION
            else -> TaskType.SEGMENTATION
        }

        val samMean = input.optJSONArray("sam_mean_rgb_0_1")
        val samStd = input.optJSONArray("sam_std_rgb_0_1")
        val meanRgb = if (task == TaskType.INTERACTIVE_SEGMENTATION && samMean != null) {
            floatArrayOf(
                samMean.getDouble(0).toFloat(),
                samMean.getDouble(1).toFloat(),
                samMean.getDouble(2).toFloat(),
            )
        } else {
            floatArrayOf(
                mean.getDouble(0).toFloat(),
                mean.getDouble(1).toFloat(),
                mean.getDouble(2).toFloat(),
            )
        }
        val stdRgb = if (task == TaskType.INTERACTIVE_SEGMENTATION && samStd != null) {
            floatArrayOf(
                samStd.getDouble(0).toFloat(),
                samStd.getDouble(1).toFloat(),
                samStd.getDouble(2).toFloat(),
            )
        } else {
            floatArrayOf(
                std.getDouble(0).toFloat(),
                std.getDouble(1).toFloat(),
                std.getDouble(2).toFloat(),
            )
        }

        val assetFile = json.optString("asset_file").takeIf { it.isNotEmpty() }
            ?: json.optString("encoder_asset").takeIf { it.isNotEmpty() }

        return LoadedModelSpec(
            modelId = json.getString("model_id"),
            assetFile = assetFile,
            decoderAssetFile = json.optString("decoder_asset").takeIf { it.isNotEmpty() },
            quantizationRuntimeAsset = json.optJSONObject("quantization")
                ?.optString("runtime_asset")
                ?.takeIf { it.isNotEmpty() }
                ?: json.optJSONObject("quantization")
                    ?.optString("encoder_runtime_asset")
                    ?.takeIf { it.isNotEmpty() },
            decoderQuantizationRuntimeAsset = json.optJSONObject("quantization")
                ?.optString("decoder_runtime_asset")
                ?.takeIf { it.isNotEmpty() },
            taskType = task,
            inputHeight = input.optInt("height", json.optInt("encoder_size", ModelSpec.INPUT_HEIGHT)),
            inputWidth = input.optInt("width", json.optInt("encoder_size", ModelSpec.INPUT_WIDTH)),
            meanRgb = meanRgb,
            stdRgb = stdRgb,
            numClasses = json.optInt("num_classes", 2),
            postprocess = output.getString("postprocess"),
            threshold = if (output.isNull("threshold")) null else output.getDouble("threshold").toFloat(),
            classNames = names,
        )
    }

    fun defaultSpec(): LoadedModelSpec = LoadedModelSpec(
        modelId = "placeholder_classification",
        assetFile = null,
        decoderAssetFile = null,
        quantizationRuntimeAsset = null,
        decoderQuantizationRuntimeAsset = null,
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

package com.example.skyedge.model

import org.json.JSONObject

object SummaryJsonBuilder {
    fun segmentation(
        spec: LoadedModelSpec,
        classPixels: Map<String, Int>,
        defectAreaRatio: Float,
    ): String {
        val root = JSONObject()
        root.put("task", "segmentation")
        root.put("model_id", spec.modelId)
        root.put("num_classes", spec.numClasses)
        root.put("defect_area_ratio", defectAreaRatio.toDouble())
        val pixels = JSONObject()
        classPixels.forEach { (name, count) -> pixels.put(name, count) }
        root.put("class_pixels", pixels)
        spec.threshold?.let { root.put("threshold", it.toDouble()) }
        return root.toString()
    }
}

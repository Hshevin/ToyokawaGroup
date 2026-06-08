package com.example.skyedge.core.domain

import org.json.JSONObject

sealed class InspectionResult {

    data class Segmentation(
        val maskPath: String,
        val inferenceMs: Long,
        val modelVersion: String,
        val summaryJson: String,
        val width: Int,
        val height: Int,
    ) : InspectionResult()

    data class Classification(
        val classIndex: Int,
        val confidence: Float,
        val label: String? = null,
        val inferenceMs: Long,
        val modelVersion: String,
    ) : InspectionResult()

    data class Error(val message: String) : InspectionResult()

    val inferenceMsOrNull: Long?
        get() = when (this) {
            is Segmentation -> inferenceMs
            is Classification -> inferenceMs
            is Error -> null
        }

    fun displayText(): String = when (this) {
        is Segmentation -> buildString {
            append("分割完成\n")
            append("模型: $modelVersion\n")
            append("mask: $maskPath\n")
            append("尺寸: ${width}x$height\n")
            append(formatDefectRatio(summaryJson))
            append("\n耗时: ${inferenceMs} ms")
        }
        is Classification -> buildString {
            append("推理完成\n")
            append("模型: $modelVersion\n")
            append("类别索引: $classIndex\n")
            append("置信度: %.4f".format(confidence))
            label?.let { append("\n标签: $it") }
            append("\n耗时: ${inferenceMs} ms")
        }
        is Error -> message
    }

    private fun formatDefectRatio(summaryJson: String): String {
        return runCatching {
            val ratio = JSONObject(summaryJson).getDouble("defect_area_ratio")
            val pct = ratio * 100.0
            if (pct <= 0.01) {
                "检测结果: 未识别到目标区域（可换道路更明显的图片）"
            } else {
                "目标区域占比: ${"%.2f".format(pct)}%"
            }
        }.getOrDefault("")
    }
}

package com.example.skyedge.model

import com.example.skyedge.domain.InspectionResult
import org.pytorch.Tensor

object ClassificationPostProcessor {
    fun fromOutputTensor(
        output: Tensor,
        labels: List<String>?,
        inferenceMs: Long,
        modelVersion: String,
    ): InspectionResult {
        return fromLogits(
            output.dataAsFloatArray,
            labels,
            inferenceMs,
            modelVersion,
        )
    }

    fun fromLogits(
        scores: FloatArray,
        labels: List<String>? = null,
        inferenceMs: Long = 0L,
        modelVersion: String = "",
    ): InspectionResult {
        if (scores.isEmpty()) {
            return InspectionResult.Error("模型输出为空")
        }
        val classIndex = scores.indices.maxByOrNull { scores[it] } ?: -1
        if (classIndex < 0) {
            return InspectionResult.Error("无法解析分类结果")
        }
        return InspectionResult.Classification(
            classIndex = classIndex,
            confidence = scores[classIndex],
            label = labels?.getOrNull(classIndex),
            inferenceMs = inferenceMs,
            modelVersion = modelVersion,
        )
    }
}

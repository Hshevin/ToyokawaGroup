package com.example.skyedge.model

import com.example.skyedge.domain.InspectionResult
import org.pytorch.Tensor

object SegmentationPostProcessor {

    data class SegmentationOutput(
        val classIndices: IntArray,
        val width: Int,
        val height: Int,
        val summaryJson: String,
    )

    fun fromOutputTensor(
        output: Tensor,
        spec: LoadedModelSpec,
    ): Result<SegmentationOutput> = runCatching {
        val shape = output.shape().map { it.toInt() }.toIntArray()
        val data = output.dataAsFloatArray
        val postprocess = spec.postprocess.lowercase()
        val parsed = when (postprocess) {
            "sigmoid_threshold" -> parseBinarySigmoid(shape, data, spec)
            "argmax" -> parseArgmax(shape, data, spec)
            else -> throw IllegalArgumentException("Unsupported postprocess: ${spec.postprocess}")
        }
        val (classIndices, width, height, counts) = parsed

        val total = (height * width).coerceAtLeast(1)
        val defectRatio = if (counts.size > 1) {
            (total - counts[0]).toFloat() / total
        } else 0f
        val names = spec.classNames.ifEmpty {
            (0 until counts.size).map { "class_$it" }
        }
        val pixelMap = linkedMapOf<String, Int>()
        for (c in counts.indices) {
            val name = names.getOrElse(c) { "class_$c" }
            pixelMap[name] = counts[c]
        }

        SegmentationOutput(
            classIndices = classIndices,
            width = width,
            height = height,
            summaryJson = SummaryJsonBuilder.segmentation(spec, pixelMap, defectRatio),
        )
    }

    private data class ParsedMask(
        val classIndices: IntArray,
        val width: Int,
        val height: Int,
        val counts: IntArray,
    )

    private fun parseBinarySigmoid(
        shape: IntArray,
        data: FloatArray,
        spec: LoadedModelSpec,
    ): ParsedMask {
        val (channels, height, width) = resolveShape(shape)
            ?: throw IllegalArgumentException("Unsupported output shape: ${shape.contentToString()}")
        if (channels != 1) {
            throw IllegalArgumentException(
                "sigmoid_threshold expects 1 channel, got $channels with shape ${shape.contentToString()}",
            )
        }
        val threshold = spec.threshold ?: 0.5f
        val classIndices = IntArray(height * width)
        val counts = IntArray(2)
        for (i in classIndices.indices) {
            val p = sigmoid(data[i])
            val clazz = if (p >= threshold) 1 else 0
            classIndices[i] = clazz
            counts[clazz]++
        }
        return ParsedMask(classIndices, width, height, counts)
    }

    private fun parseArgmax(
        shape: IntArray,
        data: FloatArray,
        spec: LoadedModelSpec,
    ): ParsedMask {
        val (channels, height, width) = resolveShape(shape)
            ?: throw IllegalArgumentException("Unsupported output shape: ${shape.contentToString()}")
        val numClasses = spec.numClasses.coerceAtLeast(channels)
        val classIndices = IntArray(height * width)
        val counts = IntArray(numClasses)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var bestC = 0
                var bestV = Float.NEGATIVE_INFINITY
                for (c in 0 until channels) {
                    val idx = c * height * width + y * width + x
                    val v = data[idx]
                    if (v > bestV) {
                        bestV = v
                        bestC = c
                    }
                }
                classIndices[y * width + x] = bestC
                counts[bestC]++
            }
        }
        return ParsedMask(classIndices, width, height, counts)
    }

    fun toResult(
        output: SegmentationOutput,
        maskPath: String,
        inferenceMs: Long,
        modelVersion: String,
    ): InspectionResult.Segmentation = InspectionResult.Segmentation(
        maskPath = maskPath,
        inferenceMs = inferenceMs,
        modelVersion = modelVersion,
        summaryJson = output.summaryJson,
        width = output.width,
        height = output.height,
    )

    private fun resolveShape(shape: IntArray): Triple<Int, Int, Int>? =
        when (shape.size) {
            4 -> Triple(shape[1], shape[2], shape[3])
            3 -> Triple(shape[0], shape[1], shape[2])
            else -> null
        }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + kotlin.math.exp((-x).toDouble()))).toFloat()
}

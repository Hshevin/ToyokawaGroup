package com.example.skyedge.model

import org.junit.Assert.assertTrue
import org.junit.Test

class SegmentationPostProcessorTest {

    private val spec = LoadedModelSpec(
        modelId = "test",
        assetFile = null,
        taskType = TaskType.SEGMENTATION,
        inputHeight = 2,
        inputWidth = 2,
        meanRgb = ModelSpec.MEAN_RGB,
        stdRgb = ModelSpec.STD_RGB,
        numClasses = 2,
        postprocess = "argmax",
        threshold = null,
        classNames = listOf("background", "defect"),
    )

    @Test
    fun summaryJsonContainsDefectRatio() {
        val json = SummaryJsonBuilder.segmentation(
            spec,
            mapOf("background" to 3, "defect" to 1),
            defectAreaRatio = 0.25f,
        )
        assertTrue(json.contains("defect_area_ratio"))
        assertTrue(json.contains("0.25"))
    }
}

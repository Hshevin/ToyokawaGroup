package com.example.skyedge.core.model

import com.example.skyedge.core.domain.InspectionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ClassificationPostProcessorTest {

    @Test
    fun argmaxPicksHighestScore() {
        val result = ClassificationPostProcessor.fromLogits(
            floatArrayOf(0.1f, 0.9f, 0.2f),
            labels = listOf("a", "b", "c"),
            inferenceMs = 42L,
            modelVersion = "model.pt",
        ) as InspectionResult.Classification

        assertEquals(1, result.classIndex)
        assertEquals(0.9f, result.confidence, 0.0001f)
        assertEquals("b", result.label)
        assertEquals(42L, result.inferenceMs)
        assertEquals("model.pt", result.modelVersion)
    }

    @Test
    fun emptyScoresReturnError() {
        val result = ClassificationPostProcessor.fromLogits(floatArrayOf())
        assertTrue(result is InspectionResult.Error)
    }
}

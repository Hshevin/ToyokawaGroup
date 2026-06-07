package com.example.skyedge.core.model

import android.graphics.Bitmap
import com.example.skyedge.core.domain.InspectionResult
import java.io.File

interface InferenceEngine {
    val isReady: Boolean
    val taskType: TaskType
    val loadedModelVersion: String?

    fun load(modelSpecAsset: String? = null): Result<Unit>
    fun infer(bitmap: Bitmap): Result<InspectionResult>
    fun infer(bitmap: Bitmap, localId: String): Result<InspectionResult>
    fun infer(bitmap: Bitmap, outputDir: File): Result<InspectionResult>
    fun close()
}

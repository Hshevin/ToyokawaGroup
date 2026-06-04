package com.example.skyedge.model

import android.graphics.Bitmap
import com.example.skyedge.domain.InspectionResult

interface InferenceEngine {
    val isReady: Boolean
    val taskType: TaskType
    val loadedModelVersion: String?

    fun load(modelSpecAsset: String? = null): Result<Unit>
    fun infer(bitmap: Bitmap): Result<InspectionResult>
    fun infer(bitmap: Bitmap, localId: String): Result<InspectionResult>
    fun close()
}

package com.example.skyedge.model

object ModelSpec {
    const val LABELS_ASSET = "labels.txt"
    const val INSPECTIONS_DIR = "inspections"

    const val INPUT_HEIGHT = 224
    const val INPUT_WIDTH = 224

    val MEAN_RGB = floatArrayOf(0.485f, 0.456f, 0.406f)
    val STD_RGB = floatArrayOf(0.229f, 0.224f, 0.225f)
}

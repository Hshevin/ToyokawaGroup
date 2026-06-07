package com.example.skyedge.model

import android.content.Context
import android.graphics.Bitmap
import com.example.skyedge.domain.InspectionResult
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File

class PytorchInferenceEngine(
    private val context: Context,
    private val labels: List<String>? = LabelLoader.load(context),
) : InferenceEngine {

    private var module: Module? = null
    private var spec: LoadedModelSpec = ModelSpecLoader.defaultSpec()

    var loadedAssetName: String? = null
        private set

    override val isReady: Boolean
        get() = module != null

    override val taskType: TaskType
        get() = spec.taskType

    override val loadedModelVersion: String?
        get() = loadedAssetName

    private val lock = Any()

    override fun load(modelSpecAsset: String?): Result<Unit> = synchronized(lock) {
        runCatching {
        module?.destroy()
        module = null
        loadedAssetName = null
        spec = ModelSpecLoader.load(context, modelSpecAsset)
        val assetFile = spec.assetFile ?: error("model_spec 缺少 asset_file")
        check(ModelLoader.assetExists(context, assetFile)) { "模型文件不存在: $assetFile" }
        module = Module.load(ModelLoader.assetFilePath(context, assetFile))
        loadedAssetName = assetFile
        }
    }

    override fun infer(bitmap: Bitmap): Result<InspectionResult> = synchronized(lock) {
        runCatching {
        if (spec.taskType == TaskType.SEGMENTATION) {
            InspectionResult.Error("分割任务请使用 infer(bitmap, localId) 或 infer(bitmap, outputDir)")
        } else {
            postprocess(forward(bitmap), context.cacheDir)
        }
        }
    }

    override fun infer(bitmap: Bitmap, localId: String): Result<InspectionResult> =
        infer(bitmap, MaskWriter.inspectionDir(context.filesDir, localId))

    override fun infer(bitmap: Bitmap, outputDir: File): Result<InspectionResult> = synchronized(lock) {
        runCatching {
        postprocess(forward(bitmap), outputDir)
        }
    }

    override fun close() {
        synchronized(lock) {
        module?.destroy()
        module = null
        loadedAssetName = null
        }
    }

    private fun forward(bitmap: Bitmap): Pair<Tensor, Long> {
        val mod = module ?: error("模型未加载")
        val started = System.nanoTime()
        val inputTensor = ImagePreprocessor.bitmapToInputTensor(bitmap, spec)
        val outputTensor = mod.forward(IValue.from(inputTensor)).toTensor()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        return outputTensor to elapsedMs
    }

    private fun postprocess(output: Pair<Tensor, Long>, outputDir: File): InspectionResult {
        val (outputTensor, elapsedMs) = output
        val version = loadedAssetName ?: "unknown"
        return when (spec.taskType) {
            TaskType.SEGMENTATION -> {
                val parsed = SegmentationPostProcessor.fromOutputTensor(outputTensor, spec)
                    .getOrElse { throw it }
                val maskFile = MaskWriter.maskFile(outputDir)
                MaskWriter.writeClassIndices(
                    parsed.classIndices,
                    parsed.width,
                    parsed.height,
                    maskFile,
                )
                SegmentationPostProcessor.toResult(parsed, maskFile.absolutePath, elapsedMs, version)
            }
            TaskType.CLASSIFICATION -> ClassificationPostProcessor.fromOutputTensor(
                outputTensor,
                labels,
                elapsedMs,
                version,
            )
        }
    }
}

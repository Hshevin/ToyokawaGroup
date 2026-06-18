package com.example.skyedge.core.model

import android.content.Context
import android.graphics.Bitmap
import com.example.skyedge.core.domain.InspectionResult
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File
import kotlin.math.roundToInt

class MobileSamInferenceEngine(
    private val context: Context,
) : InferenceEngine {

    private var encoder: Module? = null
    private var decoder: Module? = null
    private var spec: LoadedModelSpec = ModelSpecLoader.defaultSpec()
    private var cachedImage: CachedImageState? = null
    private var decoderAssetPath: String? = null
    private var decoderCacheToken: String? = null

    var loadedAssetName: String? = null
        private set

    override val isReady: Boolean
        get() = encoder != null

    val isDecoderLoaded: Boolean
        get() = decoder != null

    override val taskType: TaskType
        get() = spec.taskType

    override val loadedModelVersion: String?
        get() = loadedAssetName

    val isImageEncoded: Boolean
        get() = cachedImage != null

    private val lock = Any()

    override fun load(modelSpecAsset: String?): Result<Unit> = synchronized(lock) {
        runCatching {
            closeModules()
            spec = ModelSpecLoader.load(context, modelSpecAsset)
            val encoderAsset = spec.assetFile ?: error("model_spec 缺少 encoder_asset")
            val decoderAsset = spec.decoderAssetFile ?: error("model_spec 缺少 decoder_asset")
            require(spec.taskType == TaskType.INTERACTIVE_SEGMENTATION) {
                "MobileSamInferenceEngine 仅支持 interactive_segmentation"
            }
            check(ModelLoader.assetExists(context, encoderAsset)) { "编码器模型不存在: $encoderAsset" }
            check(ModelLoader.assetExists(context, decoderAsset)) { "解码器模型不存在: $decoderAsset" }
            if (encoderAsset.endsWith(".fp8pkg")) {
                val runtimeAsset = encoderAsset.removeSuffix(".fp8pkg") + "_runtime.pt"
                check(ModelLoader.assetExists(context, runtimeAsset)) { "编码器 FP8 运行时缺失: $runtimeAsset" }
            }
            if (decoderAsset.endsWith(".fp8pkg")) {
                val runtimeAsset = decoderAsset.removeSuffix(".fp8pkg") + "_runtime.pt"
                check(ModelLoader.assetExists(context, runtimeAsset)) { "解码器 FP8 运行时缺失: $runtimeAsset" }
            }
            val cacheToken = modelCacheToken(spec)
            encoder = Module.load(ModelLoader.resolveModelAsset(context, encoderAsset, "$cacheToken|enc"))
            decoderAssetPath = decoderAsset
            decoderCacheToken = "$cacheToken|dec"
            decoder = null
            loadedAssetName = encoderAsset
            cachedImage = null
        }
    }

    fun encode(bitmap: Bitmap, roi: MobileSamRoiBox? = null): Result<Unit> = synchronized(lock) {
        runCatching {
            val enc = encoder ?: error("模型未加载")
            val cropBitmap = if (roi != null) {
                MobileSamRoi.cropBitmap(bitmap, roi)
            } else {
                bitmap
            }
            val prepared = MobileSamPreprocessor.prepare(cropBitmap, spec)
            val started = System.nanoTime()
            val embeddings = enc.forward(IValue.from(prepared.paddedTensor)).toTensor()
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            cachedImage = prepared.toCachedState(
                embeddings = embeddings,
                encodeMs = elapsedMs,
                fullWidth = bitmap.width,
                fullHeight = bitmap.height,
                cropOffsetX = roi?.x1 ?: 0,
                cropOffsetY = roi?.y1 ?: 0,
                roiActive = roi != null,
            )
            if (cropBitmap !== bitmap) {
                cropBitmap.recycle()
            }
        }
    }

    fun prefetchDecoder(): Result<Unit> = synchronized(lock) {
        runCatching {
            ensureDecoderLoaded()
            Unit
        }
    }

    fun inferPoint(
        x: Float,
        y: Float,
        outputDir: File,
        label: Int = 1,
        baseMaskPath: String? = null,
        mergeRoi: MobileSamRoiBox? = null,
    ): Result<InspectionResult> = synchronized(lock) {
        runCatching {
            val state = cachedImage ?: error("请先导入图片并完成编码")
            val dec = ensureDecoderLoaded()
            val promptX = if (state.roiActive) x - state.cropOffsetX else x
            val promptY = if (state.roiActive) y - state.cropOffsetY else y
            require(promptX in 0f..state.origWidth.toFloat() && promptY in 0f..state.origHeight.toFloat()) {
                "点击点不在有效 ROI 内"
            }
            val started = System.nanoTime()
            val outputs = dec.forward(
                IValue.from(state.embeddings),
                IValue.from(MobileSamPreprocessor.int64Scalar(state.origHeight)),
                IValue.from(MobileSamPreprocessor.int64Scalar(state.origWidth)),
                IValue.from(MobileSamPreprocessor.int64Scalar(state.resizedHeight)),
                IValue.from(MobileSamPreprocessor.int64Scalar(state.resizedWidth)),
                IValue.from(MobileSamPreprocessor.pointTensor(promptX, promptY)),
                IValue.from(MobileSamPreprocessor.labelTensor(label)),
            ).toTuple()
            val maskLogits = outputs[0].toTensor()
            val score = outputs[1].toTensor().dataAsFloatArray.first()
            val decodeMs = (System.nanoTime() - started) / 1_000_000
            val elapsedMs = state.encodeMs + decodeMs
            val cropIndices = maskLogits.toClassIndices(
                origWidth = state.origWidth,
                origHeight = state.origHeight,
                resizedWidth = state.resizedWidth,
                resizedHeight = state.resizedHeight,
                threshold = spec.threshold ?: 0f,
            )
            val (outputWidth, outputHeight, classIndices) = if (state.roiActive) {
                Triple(
                    state.fullWidth,
                    state.fullHeight,
                    MobileSamRoi.pasteCropMaskToFull(
                        cropIndices = cropIndices,
                        cropWidth = state.origWidth,
                        cropHeight = state.origHeight,
                        fullWidth = state.fullWidth,
                        fullHeight = state.fullHeight,
                        offsetX = state.cropOffsetX,
                        offsetY = state.cropOffsetY,
                    ),
                )
            } else {
                Triple(state.origWidth, state.origHeight, cropIndices)
            }
            validateSamSegment(
                score = score,
                classIndices = classIndices,
                outputWidth = outputWidth,
                mergeRoi = mergeRoi,
            )
            val roiBox = mergeRoi ?: if (state.roiActive) {
                MobileSamRoiBox(
                    x1 = state.cropOffsetX,
                    y1 = state.cropOffsetY,
                    x2 = state.cropOffsetX + state.origWidth,
                    y2 = state.cropOffsetY + state.origHeight,
                )
            } else {
                null
            }
            val mergedIndices = MaskMerger.mergeWithPrevious(
                previousMaskPath = baseMaskPath,
                updatedIndices = classIndices,
                width = outputWidth,
                height = outputHeight,
                roi = roiBox,
            )
            val foreground = mergedIndices.count { it == 1 }
            val ratio = foreground.toFloat() / mergedIndices.size.toFloat()
            val maskFile = MaskWriter.maskFile(outputDir)
            MaskWriter.writeClassIndices(
                classIndices = mergedIndices,
                width = outputWidth,
                height = outputHeight,
                outputFile = maskFile,
            )
            val summaryJson = SummaryJsonBuilder.segmentation(
                spec = spec,
                classPixels = mapOf(
                    spec.classNames.getOrElse(0) { "background" } to mergedIndices.size - foreground,
                    spec.classNames.getOrElse(1) { "foreground" } to foreground,
                ),
                defectAreaRatio = ratio,
            )
            InspectionResult.Segmentation(
                maskPath = maskFile.absolutePath,
                inferenceMs = elapsedMs,
                modelVersion = loadedAssetName ?: "mobile_sam",
                summaryJson = summaryJson,
                width = outputWidth,
                height = outputHeight,
            ).also {
                outputDir.mkdirs()
                File(outputDir, "mobile_sam_score.txt").writeText("%.4f".format(score))
            }
        }
    }

    override fun infer(bitmap: Bitmap): Result<InspectionResult> =
        infer(bitmap, context.cacheDir)

    override fun infer(bitmap: Bitmap, localId: String): Result<InspectionResult> =
        infer(bitmap, MaskWriter.inspectionDir(context.filesDir, localId))

    override fun infer(bitmap: Bitmap, outputDir: File): Result<InspectionResult> {
        encode(bitmap).getOrElse { return Result.failure(it) }
        prefetchDecoder().getOrElse { return Result.failure(it) }
        val centerX = bitmap.width / 2f
        val centerY = bitmap.height / 2f
        return inferPoint(centerX, centerY, outputDir)
    }

    override fun close() {
        synchronized(lock) {
            closeModules()
            loadedAssetName = null
            cachedImage = null
            decoderAssetPath = null
            decoderCacheToken = null
        }
    }

    private fun ensureDecoderLoaded(): Module {
        decoder?.let { return it }
        val asset = decoderAssetPath ?: error("解码器未配置")
        val token = decoderCacheToken ?: error("解码器 cache token 缺失")
        return Module.load(ModelLoader.resolveModelAsset(context, asset, token)).also {
            decoder = it
            loadedAssetName = "${loadedAssetName ?: asset} + $asset"
        }
    }

    private fun closeModules() {
        encoder?.destroy()
        decoder?.destroy()
        encoder = null
        decoder = null
    }

    private fun modelCacheToken(spec: LoadedModelSpec): String {
        val encoder = spec.assetFile ?: spec.modelId
        val decoder = spec.decoderAssetFile ?: spec.modelId
        return "$encoder|$decoder"
    }

    private data class CachedImageState(
        val paddedTensor: Tensor,
        val embeddings: Tensor,
        val origWidth: Int,
        val origHeight: Int,
        val resizedWidth: Int,
        val resizedHeight: Int,
        val fullWidth: Int,
        val fullHeight: Int,
        val cropOffsetX: Int,
        val cropOffsetY: Int,
        val roiActive: Boolean,
        val encodeMs: Long,
    )

    private fun MobileSamPreprocessor.PreparedImage.toCachedState(
        embeddings: Tensor,
        encodeMs: Long,
        fullWidth: Int,
        fullHeight: Int,
        cropOffsetX: Int,
        cropOffsetY: Int,
        roiActive: Boolean,
    ): CachedImageState = CachedImageState(
        paddedTensor = paddedTensor,
        embeddings = embeddings,
        origWidth = origWidth,
        origHeight = origHeight,
        resizedWidth = resizedWidth,
        resizedHeight = resizedHeight,
        fullWidth = fullWidth,
        fullHeight = fullHeight,
        cropOffsetX = cropOffsetX,
        cropOffsetY = cropOffsetY,
        roiActive = roiActive,
        encodeMs = encodeMs,
    )

    private fun validateSamSegment(
        score: Float,
        classIndices: IntArray,
        outputWidth: Int,
        mergeRoi: MobileSamRoiBox?,
    ) {
        if (score < MIN_SAM_SCORE) {
            error("点击位置未识别到有效目标（置信度 ${"%.2f".format(score)}），请点在建筑上或框选区域")
        }
        val foreground = if (mergeRoi != null) {
            MaskMerger.countForegroundInBox(classIndices, outputWidth, mergeRoi)
        } else {
            classIndices.count { it > 0 }
        }
        if (foreground < MIN_FOREGROUND_PIXELS) {
            error("未识别到足够区域，请点在建筑边缘或拖拽框选")
        }
        val regionPixels = mergeRoi?.let { it.width * it.height } ?: classIndices.size
        val regionRatio = foreground.toFloat() / regionPixels.toFloat().coerceAtLeast(1f)
        if (regionRatio > MAX_REGION_FOREGROUND_RATIO) {
            error("识别范围过大，请更精确地点击或缩小框选范围")
        }
    }

    companion object {
        private const val MIN_SAM_SCORE = 0.12f
        private const val MIN_FOREGROUND_PIXELS = 24
        private const val MAX_REGION_FOREGROUND_RATIO = 0.92f
    }

    private fun Tensor.toClassIndices(
        origWidth: Int,
        origHeight: Int,
        resizedWidth: Int,
        resizedHeight: Int,
        threshold: Float,
    ): IntArray {
        val mask = dataAsFloatArray
        require(mask.size == resizedHeight * resizedWidth) {
            "mask 尺寸不匹配: ${mask.size} != ${resizedWidth}x$resizedHeight"
        }
        val classIndices = IntArray(origWidth * origHeight)
        if (resizedWidth == origWidth && resizedHeight == origHeight) {
            for (i in mask.indices) {
                classIndices[i] = if (mask[i] > threshold) 1 else 0
            }
            return classIndices
        }
        for (y in 0 until origHeight) {
            val srcY = ((y.toFloat() / origHeight) * resizedHeight)
                .roundToInt()
                .coerceIn(0, resizedHeight - 1)
            for (x in 0 until origWidth) {
                val srcX = ((x.toFloat() / origWidth) * resizedWidth)
                    .roundToInt()
                    .coerceIn(0, resizedWidth - 1)
                val value = mask[srcY * resizedWidth + srcX]
                classIndices[y * origWidth + x] = if (value > threshold) 1 else 0
            }
        }
        return classIndices
    }
}

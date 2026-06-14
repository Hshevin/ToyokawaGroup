package com.example.skyedge.core.impl

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.example.skyedge.core.api.GeoBoundsDto
import com.example.skyedge.core.api.GeoLatLngDto
import com.example.skyedge.core.api.InspectionFacade
import com.example.skyedge.core.api.InteractivePoint
import com.example.skyedge.core.api.InspectionRecordItem
import com.example.skyedge.core.api.InspectionUiState
import com.example.skyedge.core.api.MapSessionUiModel
import com.example.skyedge.core.api.ModelChoice
import com.example.skyedge.core.domain.InspectionResult
import com.example.skyedge.core.geo.GeoBounds
import com.example.skyedge.core.geo.GeoJsonIO
import com.example.skyedge.core.geo.GeoTiffReader
import com.example.skyedge.core.integration.SkyEdgeImageAnalyser
import com.example.skyedge.core.model.ImagePreprocessor
import com.example.skyedge.core.model.MaskOverlayRenderer
import com.example.skyedge.core.model.MaskMerger
import com.example.skyedge.core.model.MaskWriter
import com.example.skyedge.core.model.ModelSpec
import com.example.skyedge.core.model.MobileSamInferenceEngine
import com.example.skyedge.core.model.MobileSamRoi
import com.example.skyedge.core.model.MobileSamRoiBox
import com.example.skyedge.core.model.PytorchInferenceEngine
import imgrecord.ImageRecordRepository
import imgrecord.model.AnalyseStatus
import imgrecord.model.AnalyseType
import imgrecord.model.ImageRecord
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class InspectionFacadeImpl(
    private val context: Context,
    private val scope: CoroutineScope,
    buildingEngineFactory: (() -> PytorchInferenceEngine)? = null,
    correctionEngineFactory: (() -> MobileSamInferenceEngine)? = null,
) : InspectionFacade {

    private val buildingEngine: PytorchInferenceEngine =
        buildingEngineFactory?.invoke() ?: PytorchInferenceEngine(context)
    private var correctionEngine: MobileSamInferenceEngine? =
        correctionEngineFactory?.invoke()
    private var correctionEnginePreload: Deferred<Result<MobileSamInferenceEngine>>? = null

    private var imageRecordRepository = createRepository()
    private var currentImageUri: Uri? = null

    private val interactiveSessionDir: File
        get() = File(context.filesDir, "${ModelSpec.ANALYSIS_DIR}/mobile_sam_session").apply { mkdirs() }

    private fun createRepository(): ImageRecordRepository = ImageRecordRepository(
        context = context,
        localUrlPrefix = context.filesDir.absolutePath + "/analysis",
        analyser = SkyEdgeImageAnalyser(context, buildingEngine),
        scope = scope,
    )

    private val _state = MutableStateFlow(InspectionUiState())
    override val state: StateFlow<InspectionUiState> = _state.asStateFlow()

    override val modelChoices: List<ModelChoice> = ModelChoice.ALL

    init {
        loadModel()
        refreshHistory()
    }

    override fun loadModel(modelKey: String) {
        scope.launch {
            _state.update {
                it.copy(
                    isLoadingModel = true,
                    isModelReady = false,
                    statusMessage = "正在加载 Building 模型…",
                    selectedModelKey = ModelChoice.BUILDING.key,
                )
            }
            val result = withContext(Dispatchers.Default) {
                buildingEngine.load(ModelChoice.BUILDING.specAsset)
            }
            _state.update { current ->
                result.fold(
                    onSuccess = {
                        scheduleCorrectionEnginePreload()
                        InspectionUiState(
                            statusMessage = buildString {
                                append("Building 模型就绪\n")
                                append("导入图片后自动分割，可直接点击或框选局部修正")
                            },
                            isLoadingModel = false,
                            isModelReady = true,
                            selectedModelKey = ModelChoice.BUILDING.key,
                            recentRecords = current.recentRecords,
                            mapSession = current.mapSession,
                            interactiveImageReady = false,
                            interactivePoints = emptyList(),
                        )
                    },
                    onFailure = {
                        InspectionUiState(
                            statusMessage = "模型加载失败: ${it.message}",
                            isLoadingModel = false,
                            isModelReady = false,
                            selectedModelKey = ModelChoice.BUILDING.key,
                            recentRecords = current.recentRecords,
                            mapSession = current.mapSession,
                            interactiveImageReady = false,
                            interactivePoints = emptyList(),
                        )
                    },
                )
            }
        }
    }

    override fun switchModel(modelKey: String) {
        if (modelKey != ModelChoice.BUILDING.key) return
        loadModel(modelKey)
    }

    override fun updateStatus(message: String) {
        _state.update { it.copy(statusMessage = message) }
    }

    override suspend fun infer(uri: Uri) {
        if (_state.value.isInferring) return
        currentImageUri = uri
        _state.update {
            it.copy(
                isInferring = true,
                statusMessage = "Building 推理中…",
                lastMaskPath = null,
                buildingMaskPath = null,
                interactiveImageReady = false,
                interactivePoints = emptyList(),
                interactiveRoiActive = false,
            )
        }
        val analyseType = selectedAnalyseType()
        val outcome = coroutineScope {
            val samPreload = scheduleCorrectionEnginePreload()
            val buildingOutcome = async(Dispatchers.IO) {
                runCatching {
                    val localUrl = imageRecordRepository.insert(uri.toString(), analyseType)
                    val finalRecord = awaitRecord(localUrl)
                        ?: error("数据库记录丢失")
                    localUrl to finalRecord
                }
            }
            samPreload.await()
            buildingOutcome.await()
        }
        val recent = withContext(Dispatchers.IO) { loadRecentRecords() }
        var buildingDone = false
        _state.update { current ->
            outcome.fold(
                onSuccess = { (localUrl, finalRecord) ->
                    when (finalRecord.status) {
                        AnalyseStatus.DONE -> {
                            buildingDone = true
                            val buildingMask = SkyEdgeImageAnalyser.maskPathFromSummary(finalRecord.summaryJson)
                            current.copy(
                                isInferring = true,
                                lastMaskPath = buildingMask,
                                buildingMaskPath = buildingMask,
                                maskUpdateSeq = current.maskUpdateSeq + 1,
                                recentRecords = recent,
                                statusMessage = formatRecordStatus(localUrl, finalRecord.summaryJson) +
                                    "\n正在编码修正区域…",
                            )
                        }
                        AnalyseStatus.FAILED -> current.copy(
                            isInferring = false,
                            recentRecords = recent,
                            statusMessage = "检测失败: ${finalRecord.errInfo ?: "unknown"}",
                        )
                        AnalyseStatus.PENDING -> current.copy(
                            isInferring = false,
                            recentRecords = recent,
                            statusMessage = "检测超时: 记录仍为 pending\nlocal_url: $localUrl",
                        )
                    }
                },
                onFailure = { error ->
                    current.copy(
                        isInferring = false,
                        recentRecords = recent,
                        statusMessage = "检测失败: ${error.message}",
                    )
                },
            )
        }
        if (buildingDone) {
            prepareCorrectionState(uri, roi = null)
        } else {
            _state.update { it.copy(isInferring = false) }
        }
    }

    private fun scheduleCorrectionEnginePreload(): Deferred<Result<MobileSamInferenceEngine>> {
        correctionEngine?.takeIf { it.isReady }?.let {
            return scope.async(Dispatchers.Default) { Result.success(it) }
        }
        val existing = correctionEnginePreload
        if (existing != null && existing.isActive) {
            return existing
        }
        return scope.async(Dispatchers.Default) {
            runCatching { ensureCorrectionEngine() }
        }.also { correctionEnginePreload = it }
    }

    private suspend fun awaitCorrectionEnginePreload() {
        scheduleCorrectionEnginePreload().await().getOrThrow()
    }

    private suspend fun ensureCorrectionEngine(): MobileSamInferenceEngine {
        correctionEngine?.takeIf { it.isReady }?.let { return it }
        val engine = correctionEngine ?: MobileSamInferenceEngine(context).also { correctionEngine = it }
        engine.load(ModelChoice.MOBILE_SAM.specAsset).getOrThrow()
        return engine
    }

    private suspend fun prepareCorrection(uri: Uri, roi: MobileSamRoiBox?): EncodeOutcome {
        val bitmap = ImagePreprocessor.loadOrientedBitmap(context, uri)
            ?: error("无法读取图片")
        val effectiveRoi = roi ?: (_state.value.buildingMaskPath ?: _state.value.lastMaskPath)?.let { maskPath ->
            MobileSamRoi.boxFromMaskFile(File(maskPath), bitmap.width, bitmap.height)
        }
        awaitCorrectionEnginePreload()
        val engine = correctionEngine ?: error("修正引擎未加载")
        engine.encode(bitmap, effectiveRoi).getOrThrow()
        val outcome = EncodeOutcome(bitmap.width, bitmap.height, effectiveRoi != null)
        bitmap.recycle()
        return outcome
    }

    private fun prefetchCorrectionDecoderInBackground() {
        scope.launch(Dispatchers.Default) {
            correctionEngine?.prefetchDecoder()
        }
    }

    private suspend fun prepareCorrectionState(uri: Uri, roi: MobileSamRoiBox?) {
        val outcome = withContext(Dispatchers.Default) {
            runCatching { prepareCorrection(uri, roi) }
        }
        _state.update { current ->
            outcome.fold(
                onSuccess = { done ->
                    current.copy(
                        isInferring = false,
                        interactiveImageReady = true,
                        interactiveImageWidth = done.imageWidth,
                        interactiveImageHeight = done.imageHeight,
                        interactiveRoiActive = done.roiActive,
                        statusMessage = current.statusMessage.trimEnd() + buildString {
                            append("\n")
                            append("局部修正已就绪：点击图片修正，或拖拽框选区域")
                            if (done.roiActive) append("（已启用 ROI）")
                        },
                    )
                },
                onFailure = { error ->
                    current.copy(
                        isInferring = false,
                        interactiveImageReady = false,
                        statusMessage = current.statusMessage.trimEnd() +
                            "\n局部修正引擎准备失败: ${error.message}",
                    )
                },
            )
        }
        if (outcome.isSuccess) {
            prefetchCorrectionDecoderInBackground()
        }
    }

    override suspend fun encodeInteractiveImage(uri: Uri) {
        prepareCorrectionState(uri, roi = null)
    }

    override suspend fun inferInteractivePoint(x: Float, y: Float, imageWidth: Int, imageHeight: Int) {
        if (_state.value.isInferring) return
        val uri = currentImageUri ?: return updateStatus("请先导入图片")
        if (correctionEngine?.isImageEncoded != true) {
            prepareCorrectionState(uri, roi = null)
        }
        val mobileEngine = correctionEngine?.takeIf { it.isImageEncoded }
        if (mobileEngine == null) {
            updateStatus(
                _state.value.statusMessage.trimEnd() +
                    "\n局部修正尚未就绪，请等待「局部修正已就绪」后再点击",
            )
            return
        }
        val decoderReady = mobileEngine.isDecoderLoaded
        _state.update {
            it.copy(
                isInferring = true,
                statusMessage = if (decoderReady) "局部修正中…" else "首次点击：解码器加载中…",
            )
        }
        val mappedX = x.coerceIn(0f, imageWidth.toFloat())
        val mappedY = y.coerceIn(0f, imageHeight.toFloat())
        val baseMaskPath = _state.value.buildingMaskPath ?: _state.value.lastMaskPath
        val outcome = withContext(Dispatchers.Default) {
            mobileEngine.inferPoint(
                mappedX,
                mappedY,
                interactiveSessionDir,
                baseMaskPath = baseMaskPath,
            )
        }
        _state.update { current ->
            outcome.fold(
                onSuccess = { result ->
                    current.copy(
                        isInferring = false,
                        lastMaskPath = (result as? InspectionResult.Segmentation)?.maskPath,
                        maskUpdateSeq = current.maskUpdateSeq + 1,
                        interactivePoints = current.interactivePoints + InteractivePoint(mappedX, mappedY),
                        statusMessage = result.displayText() + "\n可继续点击或框选修正",
                    )
                },
                onFailure = {
                    current.copy(
                        isInferring = false,
                        statusMessage = "局部修正失败: ${it.message}",
                    )
                },
            )
        }
    }

    override suspend fun selectCorrectionRoi(
        uri: Uri,
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        imageWidth: Int,
        imageHeight: Int,
    ) {
        if (_state.value.isInferring) return
        currentImageUri = uri
        val box = MobileSamRoiBox.clamp(
            x1 = x1.toInt(),
            y1 = y1.toInt(),
            x2 = x2.toInt(),
            y2 = y2.toInt(),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
        _state.update {
            it.copy(
                isInferring = true,
                statusMessage = "已框选区域，正在更新 ROI…",
            )
        }
        prepareCorrectionState(uri, roi = box)
        if (!_state.value.interactiveImageReady) return

        val buildingPath = _state.value.buildingMaskPath
        val boxApplied = buildingPath?.let { path ->
            withContext(Dispatchers.Default) {
                runCatching {
                    MaskMerger.buildBoxSelectionFromBuilding(
                        buildingMaskPath = path,
                        box = box,
                        width = imageWidth,
                        height = imageHeight,
                    )
                }.getOrNull()
            }
        }
        val minBoxPixels = (box.width * box.height * 0.005f).toInt().coerceAtLeast(48)
        if (boxApplied != null && MaskMerger.countForegroundInBox(boxApplied, imageWidth, box) >= minBoxPixels) {
            val maskFile = MaskWriter.maskFile(interactiveSessionDir)
            withContext(Dispatchers.Default) {
                MaskWriter.writeClassIndices(
                    classIndices = boxApplied,
                    width = imageWidth,
                    height = imageHeight,
                    outputFile = maskFile,
                )
            }
            val ratio = MaskMerger.defectAreaRatio(boxApplied)
            val (markerX, markerY) = MobileSamRoi.promptPointInBox(
                maskFile = buildingPath?.let { File(it) },
                box = box,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
            )
            _state.update { current ->
                current.copy(
                    isInferring = false,
                    lastMaskPath = maskFile.absolutePath,
                    maskUpdateSeq = current.maskUpdateSeq + 1,
                    interactiveRoiActive = true,
                    interactivePoints = current.interactivePoints + InteractivePoint(markerX, markerY),
                    statusMessage = buildString {
                        append("框选已应用：选中框内全部 Building 检测区域\n")
                        append("目标区域占比: ${"%.2f".format(ratio * 100)}%\n")
                        append("若框内有漏检，可在框内单击用 MobileSAM 补充")
                    },
                )
            }
            return
        }

        val buildingMask = buildingPath?.let { File(it) }
        val (promptX, promptY) = MobileSamRoi.promptPointInBox(
            maskFile = buildingMask,
            box = box,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
        _state.update {
            it.copy(statusMessage = "框内 Building 检测较少，改用 MobileSAM 点选分割…")
        }
        inferInteractivePoint(promptX, promptY, imageWidth, imageHeight)
    }

    override suspend fun runMobileSamDemo(demoName: String) {
        if (_state.value.isInferring || _state.value.isLoadingModel) return
        _state.update { it.copy(isInferring = true, statusMessage = "MobileSAM 演示加载中…") }
        val specAsset = ModelChoice.MOBILE_SAM.specAsset
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                ensureCorrectionEngine()
                val specJson = context.assets.open(specAsset).bufferedReader().use { it.readText() }
                val root = JSONObject(specJson)
                val demoAssets = root.getJSONObject("demo_assets")
                val pointsJson = context.assets.open(demoAssets.getString("points_json"))
                    .bufferedReader().use { it.readText() }
                val examples = JSONObject(pointsJson).getJSONArray("examples")
                var targetImageAsset = demoAssets.getString("building_image")
                var pointX = 99f
                var pointY = 14f
                var demoExample: org.json.JSONObject? = null
                for (i in 0 until examples.length()) {
                    val example = examples.getJSONObject(i)
                    if (example.getString("name") == demoName) {
                        targetImageAsset = demoAssets.getString("building_image")
                        val positive = example.getJSONArray("positive_points").getJSONArray(0)
                        pointX = positive.getDouble(0).toFloat()
                        pointY = positive.getDouble(1).toFloat()
                        demoExample = example
                        break
                    }
                }
                val assetPath = targetImageAsset
                val bitmap = context.assets.open(assetPath).use { stream ->
                    BitmapFactory.decodeStream(stream)
                } ?: error("无法读取演示图片: $assetPath")
                val roi = demoExample?.takeIf {
                    it.optBoolean("crop_to_box", false) && it.has("box")
                }?.let {
                    MobileSamRoiBox.fromJsonArray(
                        it.getJSONArray("box"),
                        bitmap.width,
                        bitmap.height,
                    )
                }
                val mobileEngine = ensureCorrectionEngine()
                mobileEngine.encode(bitmap, roi).getOrThrow()
                mobileEngine.prefetchDecoder().getOrThrow()
                val result = mobileEngine.inferPoint(
                    pointX,
                    pointY,
                    interactiveSessionDir,
                    baseMaskPath = null,
                ).getOrThrow()
                val imageWidth = bitmap.width
                val imageHeight = bitmap.height
                bitmap.recycle()
                DemoOutcome(result, pointX, pointY, imageWidth, imageHeight, roi != null)
            }
        }
        _state.update { current ->
            outcome.fold(
                onSuccess = { done ->
                    current.copy(
                        isInferring = false,
                        isModelReady = true,
                        selectedModelKey = ModelChoice.MOBILE_SAM.key,
                        interactiveImageReady = true,
                        interactiveImageWidth = done.imageWidth,
                        interactiveImageHeight = done.imageHeight,
                        interactiveRoiActive = done.roiActive,
                        interactivePoints = listOf(InteractivePoint(done.pointX, done.pointY)),
                        lastMaskPath = (done.result as? InspectionResult.Segmentation)?.maskPath,
                        maskUpdateSeq = current.maskUpdateSeq + 1,
                        statusMessage = "演示完成 ($demoName)\n${done.result.displayText()}",
                    )
                },
                onFailure = {
                    current.copy(
                        isInferring = false,
                        statusMessage = "MobileSAM 演示失败: ${it.message}",
                    )
                },
            )
        }
    }

    override suspend fun loadGeoTiff(uri: Uri) {
        if (_state.value.isInferring) return
        _state.update {
            it.copy(
                statusMessage = "正在读取 GeoTIFF…",
                mapSession = it.mapSession?.copy(isLoadingGeo = true, geoError = null),
            )
        }
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val sessionId = UUID.randomUUID().toString()
                val sessionDir = File(context.filesDir, "${ModelSpec.ANALYSIS_DIR}/$sessionId").apply { mkdirs() }
                val sourceFile = File(sessionDir, "source.tiff")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(sourceFile).use { output -> input.copyTo(output) }
                } ?: error("无法读取 GeoTIFF: $uri")

                val loaded = GeoTiffReader.decode(sourceFile.readBytes())
                val previewFile = File(sessionDir, "preview.png")
                FileOutputStream(previewFile).use { out ->
                    loaded.previewBitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                loaded.previewBitmap.recycle()

                val metadata = loaded.metadata.copy(orthoPreviewPath = previewFile.absolutePath)
                GeoJsonIO.write(metadata, GeoJsonIO.geoFile(sessionDir))
                sessionId to metadata
            }
        }
        _state.update { current ->
            outcome.fold(
                onSuccess = { (sessionId, metadata) ->
                    current.copy(
                        statusMessage = "GeoTIFF 已加载，请开始检测或调整图层",
                        mapSession = MapSessionUiModel(
                            sessionId = sessionId,
                            boundsGcj02 = metadata.boundsGcj02.toDto(),
                            orthoPreviewPath = metadata.orthoPreviewPath.orEmpty(),
                            isLoadingGeo = false,
                        ),
                    )
                },
                onFailure = { error ->
                    current.copy(
                        statusMessage = "GeoTIFF 加载失败: ${error.message}",
                        mapSession = current.mapSession?.copy(
                            isLoadingGeo = false,
                            geoError = error.message ?: error.toString(),
                        ),
                    )
                },
            )
        }
    }

    override suspend fun inferMapSession() {
        val session = _state.value.mapSession ?: run {
            updateStatus("请先导入 GeoTIFF")
            return
        }
        if (_state.value.isInferring) return
        _state.update {
            it.copy(
                isInferring = true,
                statusMessage = "地图影像推理中…",
                lastMaskPath = null,
                mapSession = session.copy(maskOverlayPath = null),
            )
        }
        val analyseType = selectedAnalyseType()
        val modelKey = _state.value.selectedModelKey
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val sessionDir = File(context.filesDir, "${ModelSpec.ANALYSIS_DIR}/${session.sessionId}")
                val localUrl = sessionDir.absolutePath + File.separator
                val previewFile = File(session.orthoPreviewPath)
                require(previewFile.exists()) { "地图预览不存在: ${session.orthoPreviewPath}" }
                imageRecordRepository.delete(localUrl)
                imageRecordRepository.insertAt(localUrl, Uri.fromFile(previewFile).toString(), analyseType)
                val finalRecord = awaitRecord(localUrl) ?: error("数据库记录丢失")
                if (finalRecord.status != AnalyseStatus.DONE) return@runCatching MapInferOutcome(finalRecord, null)

                val maskPath = SkyEdgeImageAnalyser.maskPathFromSummary(finalRecord.summaryJson)
                    ?: error("检测结果缺少 mask_path")
                val metadata = GeoJsonIO.read(GeoJsonIO.geoFile(sessionDir))
                    ?: error("地图会话缺少 geo.json")
                val overlayFile = File(sessionDir, "mask_overlay.png")
                val overlayPath = MaskOverlayRenderer.renderMaskFile(
                    maskPath = maskPath,
                    outputFile = overlayFile,
                    targetWidth = metadata.previewWidth,
                    targetHeight = metadata.previewHeight,
                    modelKey = modelKey,
                    alpha = 1f,
                )
                GeoJsonIO.write(
                    metadata.copy(maskOverlayPath = overlayPath),
                    GeoJsonIO.geoFile(sessionDir),
                )
                MapInferOutcome(finalRecord, overlayPath)
            }
        }
        val recent = withContext(Dispatchers.IO) { loadRecentRecords() }
        _state.update { current ->
            outcome.fold(
                onSuccess = { done ->
                    when (done.record.status) {
                        AnalyseStatus.DONE -> current.copy(
                            isInferring = false,
                            lastMaskPath = SkyEdgeImageAnalyser.maskPathFromSummary(done.record.summaryJson),
                            maskUpdateSeq = current.maskUpdateSeq + 1,
                            recentRecords = recent,
                            statusMessage = formatRecordStatus(done.record.localUrl, done.record.summaryJson),
                            mapSession = current.mapSession?.copy(maskOverlayPath = done.maskOverlayPath),
                        )
                        AnalyseStatus.FAILED -> current.copy(
                            isInferring = false,
                            recentRecords = recent,
                            statusMessage = "地图检测失败: ${done.record.errInfo ?: "unknown"}",
                        )
                        AnalyseStatus.PENDING -> current.copy(
                            isInferring = false,
                            recentRecords = recent,
                            statusMessage = "地图检测超时: 记录仍为 pending\nlocal_url: ${done.record.localUrl}",
                        )
                    }
                },
                onFailure = { error ->
                    current.copy(
                        isInferring = false,
                        recentRecords = recent,
                        statusMessage = "地图检测失败: ${error.message}",
                    )
                },
            )
        }
    }

    override fun setMapLayerVisibility(showOrtho: Boolean, showMask: Boolean) {
        _state.update { current ->
            current.copy(
                mapSession = current.mapSession?.copy(
                    showOrtho = showOrtho,
                    showMask = showMask,
                ),
            )
        }
    }

    override fun setMaskAlpha(alpha: Float) {
        _state.update { current ->
            current.copy(mapSession = current.mapSession?.copy(maskAlpha = alpha.coerceIn(0f, 1f)))
        }
    }

    override fun clearMapSession() {
        _state.update { it.copy(mapSession = null, statusMessage = "地图会话已清除") }
    }

    override fun refreshHistory() {
        scope.launch {
            val recent = withContext(Dispatchers.IO) { loadRecentRecords() }
            _state.update { it.copy(recentRecords = recent) }
        }
    }

    override suspend fun benchmark(uri: Uri, runs: Int) {
        if (!buildingEngine.isReady || _state.value.isInferring || runs <= 0) return
        _state.update {
            it.copy(
                isInferring = true,
                statusMessage = "基准测试中…（$runs 次）",
                lastMaskPath = null,
            )
        }
        val localIdPrefix = UUID.randomUUID().toString()
        val outcome = withContext(Dispatchers.Default) {
            val bitmap = ImagePreprocessor.loadOrientedBitmap(context, uri)
                ?: return@withContext Result.failure<BenchmarkOutcome>(
                    IllegalStateException("无法读取图片"),
                )
            runCatching {
                val msList = mutableListOf<Long>()
                var lastResult: InspectionResult? = null
                repeat(runs) { idx ->
                    val result = buildingEngine.infer(bitmap, "${localIdPrefix}_$idx").getOrThrow()
                    msList += result.inferenceMsOrNull
                        ?: throw IllegalStateException("无法读取推理耗时")
                    lastResult = result
                }
                bitmap.recycle()
                val sorted = msList.sorted()
                BenchmarkOutcome(
                    avgMs = msList.average(),
                    p90Ms = sorted[(sorted.size * 0.9).toInt().coerceAtLeast(1) - 1],
                    timesMs = msList,
                    lastResult = lastResult ?: error("缺少推理结果"),
                )
            }
        }
        _state.update { current ->
            outcome.fold(
                onSuccess = { done ->
                    val summary = buildString {
                        append("基准测试完成（$runs 次）\n")
                        append("模型: ${buildingEngine.loadedModelVersion ?: "unknown"}\n")
                        append("avg: ${"%.1f".format(done.avgMs)} ms\n")
                        append("p90: ${done.p90Ms} ms\n")
                        append("times: ${done.timesMs.joinToString(",")}")
                    }
                    current.copy(
                        isInferring = false,
                        lastMaskPath = (done.lastResult as? InspectionResult.Segmentation)?.maskPath,
                        maskUpdateSeq = current.maskUpdateSeq + 1,
                        statusMessage = "${done.lastResult.displayText()}\n$summary",
                    )
                },
                onFailure = {
                    current.copy(
                        isInferring = false,
                        statusMessage = "基准测试失败: ${it.message}",
                    )
                },
            )
        }
    }

    override fun close() {
        buildingEngine.close()
        correctionEngine?.close()
        correctionEngine = null
    }

    private suspend fun awaitRecord(localUrl: String): ImageRecord? =
        withTimeoutOrNull(INFERENCE_WAIT_MS) {
            var record = imageRecordRepository.queryByLocalUrl(localUrl)
            while (record?.status == AnalyseStatus.PENDING) {
                delay(POLL_INTERVAL_MS)
                record = imageRecordRepository.queryByLocalUrl(localUrl)
            }
            record
        }

    private suspend fun loadRecentRecords(): List<InspectionRecordItem> =
        imageRecordRepository.traverse()
            .sortedByDescending { it.time }
            .take(RECENT_RECORD_LIMIT)
            .map { it.toItem() }

    private fun selectedAnalyseType(): AnalyseType = AnalyseType.BUILDING

    private fun GeoBounds.toDto(): GeoBoundsDto =
        GeoBoundsDto(
            sw = GeoLatLngDto(lat = sw.latitude, lng = sw.longitude),
            ne = GeoLatLngDto(lat = ne.latitude, lng = ne.longitude),
        )

    private fun ImageRecord.toItem(): InspectionRecordItem {
        val ratioLine = runCatching {
            JSONObject(summaryJson).optDouble("defect_area_ratio", -1.0)
        }.getOrDefault(-1.0).takeIf { it >= 0 }?.let { "占比 ${"%.1f".format(it * 100)}%" } ?: ""
        return InspectionRecordItem(
            localUrl = localUrl,
            analyseType = analyseType.name,
            status = status.name,
            detail = ratioLine,
        )
    }

    private fun formatRecordStatus(localUrl: String, summaryJson: String): String = buildString {
        append("检测完成\n")
        append("local_url: $localUrl\n")
        runCatching {
            val json = JSONObject(summaryJson)
            json.optString("model_version").takeIf { it.isNotBlank() }?.let {
                append("模型: $it\n")
            }
            json.optString("mask_path").takeIf { it.isNotBlank() }?.let {
                append("mask: $it\n")
            }
            if (json.has("defect_area_ratio")) {
                val pct = json.getDouble("defect_area_ratio") * 100.0
                append(
                    if (pct <= 0.01) {
                        "检测结果: 未识别到目标区域（可换道路更明显的图片）"
                    } else {
                        "目标区域占比: ${"%.2f".format(pct)}%"
                    },
                )
                append('\n')
            }
            json.optLong("inference_ms").takeIf { it > 0 }?.let {
                append("耗时: $it ms")
            }
        }.onFailure {
            append(summaryJson)
        }
    }

    private data class BenchmarkOutcome(
        val avgMs: Double,
        val p90Ms: Long,
        val timesMs: List<Long>,
        val lastResult: InspectionResult,
    )

    private data class MapInferOutcome(
        val record: ImageRecord,
        val maskOverlayPath: String?,
    )

    private data class EncodeOutcome(
        val imageWidth: Int,
        val imageHeight: Int,
        val roiActive: Boolean,
    )

    private data class DemoOutcome(
        val result: InspectionResult,
        val pointX: Float,
        val pointY: Float,
        val imageWidth: Int,
        val imageHeight: Int,
        val roiActive: Boolean,
    )

    companion object {
        private const val POLL_INTERVAL_MS = 100L
        private const val INFERENCE_WAIT_MS = 180_000L
        private const val RECENT_RECORD_LIMIT = 5
    }
}

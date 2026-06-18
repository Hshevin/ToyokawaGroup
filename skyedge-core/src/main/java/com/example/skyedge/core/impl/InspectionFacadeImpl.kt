package com.example.skyedge.core.impl

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.location.LocationManager
import android.net.Uri
import android.content.pm.PackageManager
import com.example.skyedge.core.api.AnomalyTypeUi
import com.example.skyedge.core.api.AnomalyUiModel
import com.example.skyedge.core.api.AnomalyUpdateRequest
import com.example.skyedge.core.api.BoundingBoxDto
import com.example.skyedge.core.api.CompareSessionUiModel
import com.example.skyedge.core.api.CreateTaskRequest
import com.example.skyedge.core.api.DisasterPointUiModel
import com.example.skyedge.core.api.DisasterTrackUiModel
import com.example.skyedge.core.api.GeoBoundsDto
import com.example.skyedge.core.api.GeoLatLngDto
import com.example.skyedge.core.api.InspectionFacade
import com.example.skyedge.core.api.InteractivePoint
import com.example.skyedge.core.api.InspectionRecordItem
import com.example.skyedge.core.api.InspectionUiState
import com.example.skyedge.core.api.MapSessionUiModel
import com.example.skyedge.core.api.ModelChoice
import com.example.skyedge.core.api.ReportDraftUiModel
import com.example.skyedge.core.api.ReportExportResult
import com.example.skyedge.core.api.ReportFormat
import com.example.skyedge.core.api.ReviewAnomalyRequest
import com.example.skyedge.core.api.ReviewStatusUi
import com.example.skyedge.core.api.SceneTypeUi
import com.example.skyedge.core.api.SubmitAnomalyRequest
import com.example.skyedge.core.api.TaskPriorityUi
import com.example.skyedge.core.api.TaskStatusUi
import com.example.skyedge.core.api.TaskUiModel
import com.example.skyedge.core.domain.InspectionResult
import com.example.skyedge.core.geo.GeoBounds
import com.example.skyedge.core.geo.GeoAnomalyLocationResolver
import com.example.skyedge.core.geo.GeoJsonIO
import com.example.skyedge.core.geo.GeoTiffReader
import com.example.skyedge.core.integration.SkyEdgeImageAnalyser
import com.example.skyedge.core.model.AnomalyThumbnailGenerator
import com.example.skyedge.core.model.ImagePreprocessor
import com.example.skyedge.core.model.MaskInstanceExtractor
import com.example.skyedge.core.model.MaskOverlayRenderer
import com.example.skyedge.core.model.MaskMerger
import com.example.skyedge.core.model.MaskWriter
import com.example.skyedge.core.model.ModelSpec
import com.example.skyedge.core.model.MobileSamInferenceEngine
import com.example.skyedge.core.model.MobileSamRoi
import com.example.skyedge.core.model.MobileSamRoiBox
import com.example.skyedge.core.model.PytorchInferenceEngine
import com.example.skyedge.core.model.ReportImageComposer
import imgrecord.AnomalyRepository
import imgrecord.ImageRecordRepository
import imgrecord.TaskRepository
import imgrecord.model.AnomalyRecord
import imgrecord.model.AnomalyType
import imgrecord.model.AnalyseStatus
import imgrecord.model.AnalyseType
import imgrecord.model.BoundingBoxRecord
import imgrecord.model.ImageRecord
import imgrecord.model.ReviewStatus
import imgrecord.model.SceneType
import imgrecord.model.TaskPriority
import imgrecord.model.TaskRecord
import imgrecord.model.TaskStatus
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.json.JSONArray
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
import java.util.concurrent.TimeoutException
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
    private val taskRepository = TaskRepository(context)
    private val anomalyRepository = AnomalyRepository(context)

    private val _state = MutableStateFlow(InspectionUiState())
    override val state: StateFlow<InspectionUiState> = _state.asStateFlow()

    override val modelChoices: List<ModelChoice> = ModelChoice.ALL

    init {
        loadModel()
        refreshHistory()
        refreshTasks()
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
                        current.copy(
                            statusMessage = buildString {
                                append("Building 模型就绪\n")
                                append("导入图片后自动分割，可直接点击或框选局部修正")
                            },
                            isLoadingModel = false,
                            isModelReady = true,
                            selectedModelKey = ModelChoice.BUILDING.key,
                            interactiveImageReady = false,
                            interactivePoints = emptyList(),
                        )
                    },
                    onFailure = {
                        current.copy(
                            statusMessage = "模型加载失败: ${it.message}",
                            isLoadingModel = false,
                            isModelReady = false,
                            selectedModelKey = ModelChoice.BUILDING.key,
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
                    val task = ensureActiveTask(SceneType.BUILDING)
                    val localUrl = imageRecordRepository.insert(uri.toString(), analyseType, task.id)
                    val finalRecord = awaitRecord(localUrl)
                        ?: error("数据库记录丢失")
                    createDraftAnomaliesIfNeeded(task.id, finalRecord)
                    localUrl to finalRecord
                }
            }
            samPreload.await()
            buildingOutcome.await()
        }
        val recent = withContext(Dispatchers.IO) { loadRecentRecords() }
        val tasks = withContext(Dispatchers.IO) { loadTasksWithStats() }
        val anomalies = withContext(Dispatchers.IO) { loadActiveAnomalies() }
        val reportDraft = withContext(Dispatchers.IO) {
            _state.value.activeTask?.let { buildReportDraft(it.id) }
        }
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
                                tasks = tasks,
                                anomalies = anomalies,
                                reportDraft = reportDraft,
                                statusMessage = formatRecordStatus(localUrl, finalRecord.summaryJson) +
                                    "\n正在编码修正区域…",
                            )
                        }
                        AnalyseStatus.FAILED -> current.copy(
                            isInferring = false,
                            recentRecords = recent,
                            tasks = tasks,
                            anomalies = anomalies,
                            statusMessage = "检测失败: ${finalRecord.errInfo ?: "unknown"}",
                        )
                        AnalyseStatus.PENDING -> current.copy(
                            isInferring = false,
                            recentRecords = recent,
                            tasks = tasks,
                            anomalies = anomalies,
                            statusMessage = "检测超时: 记录仍为 pending\nlocal_url: $localUrl",
                        )
                    }
                },
                onFailure = { error ->
                    current.copy(
                        isInferring = false,
                        recentRecords = recent,
                        tasks = tasks,
                        anomalies = anomalies,
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
        awaitCorrectionEnginePreload()
        val engine = correctionEngine ?: error("修正引擎未加载")
        engine.encode(bitmap, roi).getOrThrow()
        val outcome = EncodeOutcome(bitmap.width, bitmap.height, roi != null)
        bitmap.recycle()
        return outcome
    }

    private fun currentWorkingMaskPath(): String? =
        _state.value.lastMaskPath?.takeIf { File(it).exists() }
            ?: _state.value.buildingMaskPath?.takeIf { File(it).exists() }

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
            withContext(Dispatchers.Default) {
                correctionEngine?.prefetchDecoder()
            }
        }
    }

    override suspend fun encodeInteractiveImage(uri: Uri) {
        prepareCorrectionState(uri, roi = null)
    }

    override suspend fun inferInteractivePoint(x: Float, y: Float, imageWidth: Int, imageHeight: Int) {
        runInteractiveInference(
            x = x,
            y = y,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            mergeRoi = null,
            loadingMessage = "局部修正中…",
            successSuffix = "可继续点击或框选修正",
        )
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
        currentImageUri = uri
        val box = MobileSamRoiBox.clamp(
            x1 = x1.toInt(),
            y1 = y1.toInt(),
            x2 = x2.toInt(),
            y2 = y2.toInt(),
            imageWidth = imageWidth,
            imageHeight = imageHeight,
        )
        if (currentWorkingMaskPath() == null) {
            updateStatus("请先完成 Building 检测后再框选")
            return
        }
        val promptX = (box.x1 + box.x2) / 2f
        val promptY = (box.y1 + box.y2) / 2f
        runInteractiveInference(
            x = promptX,
            y = promptY,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            mergeRoi = box,
            loadingMessage = "框选 SAM 修正中…",
            successSuffix = "框内区域已叠加到原 mask，可继续点选补充",
            recordInteractivePoint = false,
        )
    }

    private suspend fun runInteractiveInference(
        x: Float,
        y: Float,
        imageWidth: Int,
        imageHeight: Int,
        mergeRoi: MobileSamRoiBox?,
        loadingMessage: String,
        successSuffix: String,
        recordInteractivePoint: Boolean = true,
    ) {
        if (_state.value.isInferring) return
        val uri = currentImageUri ?: return updateStatus("请先导入图片")
        if (correctionEngine?.isImageEncoded != true) {
            prepareCorrectionState(uri, roi = null)
        }
        val mobileEngine = correctionEngine?.takeIf { it.isImageEncoded }
        if (mobileEngine == null) {
            updateStatus(
                _state.value.statusMessage.trimEnd() +
                    "\n局部修正尚未就绪，请等待「局部修正已就绪」后再操作",
            )
            return
        }
        val decoderReady = mobileEngine.isDecoderLoaded
        _state.update {
            it.copy(
                isInferring = true,
                statusMessage = if (decoderReady) loadingMessage else "首次操作：解码器加载中…",
            )
        }
        val mappedX = x.coerceIn(0f, imageWidth.toFloat())
        val mappedY = y.coerceIn(0f, imageHeight.toFloat())
        val baseMaskPath = currentWorkingMaskPath()
        val outcome = withContext(Dispatchers.Default) {
            withTimeoutOrNull(INTERACTIVE_SAM_TIMEOUT_MS) {
                mobileEngine.inferPoint(
                    mappedX,
                    mappedY,
                    interactiveSessionDir,
                    baseMaskPath = baseMaskPath,
                    mergeRoi = mergeRoi,
                )
            } ?: Result.failure(TimeoutException("SAM 推理超时，请重试"))
        }
        _state.update { current ->
            outcome.fold(
                onSuccess = { result ->
                    current.copy(
                        isInferring = false,
                        lastMaskPath = (result as? InspectionResult.Segmentation)?.maskPath,
                        maskUpdateSeq = current.maskUpdateSeq + 1,
                        interactivePoints = if (recordInteractivePoint) {
                            current.interactivePoints + InteractivePoint(mappedX, mappedY)
                        } else {
                            current.interactivePoints
                        },
                        statusMessage = result.displayText() + "\n$successSuffix",
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
                val expectedSize = context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                    descriptor.statSize
                } ?: -1L
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(sourceFile).use { output -> input.copyTo(output) }
                } ?: error("无法读取 GeoTIFF: $uri")

                val bytes = sourceFile.readBytes()
                if (expectedSize > 0 && bytes.size.toLong() != expectedSize) {
                    error(
                        "GeoTIFF 文件未完整读取（已复制 ${bytes.size} 字节，期望 $expectedSize 字节）。" +
                            "请从 Download 重新选择 .tif 文件",
                    )
                }

                val loaded = GeoTiffReader.decode(bytes)
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
                val task = ensureActiveTask(SceneType.BUILDING)
                val sessionDir = File(context.filesDir, "${ModelSpec.ANALYSIS_DIR}/${session.sessionId}")
                val localUrl = sessionDir.absolutePath + File.separator
                val previewFile = File(session.orthoPreviewPath)
                require(previewFile.exists()) { "地图预览不存在: ${session.orthoPreviewPath}" }
                imageRecordRepository.delete(localUrl)
                imageRecordRepository.insertAt(localUrl, Uri.fromFile(previewFile).toString(), analyseType, task.id)
                val finalRecord = awaitRecord(localUrl) ?: error("数据库记录丢失")
                if (finalRecord.status != AnalyseStatus.DONE) return@runCatching MapInferOutcome(finalRecord, null)
                createDraftAnomaliesIfNeeded(task.id, finalRecord)

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
        val tasks = withContext(Dispatchers.IO) { loadTasksWithStats() }
        val anomalies = withContext(Dispatchers.IO) { loadActiveAnomalies() }
        val reportDraft = withContext(Dispatchers.IO) {
            _state.value.activeTask?.let { buildReportDraft(it.id) }
        }
        _state.update { current ->
            outcome.fold(
                onSuccess = { done ->
                    when (done.record.status) {
                        AnalyseStatus.DONE -> current.copy(
                            isInferring = false,
                            lastMaskPath = SkyEdgeImageAnalyser.maskPathFromSummary(done.record.summaryJson),
                            maskUpdateSeq = current.maskUpdateSeq + 1,
                            recentRecords = recent,
                            tasks = tasks,
                            anomalies = anomalies,
                            reportDraft = reportDraft,
                            statusMessage = formatRecordStatus(done.record.localUrl, done.record.summaryJson),
                            mapSession = current.mapSession?.copy(maskOverlayPath = done.maskOverlayPath),
                        )
                        AnalyseStatus.FAILED -> current.copy(
                            isInferring = false,
                            recentRecords = recent,
                            tasks = tasks,
                            anomalies = anomalies,
                            statusMessage = "地图检测失败: ${done.record.errInfo ?: "unknown"}",
                        )
                        AnalyseStatus.PENDING -> current.copy(
                            isInferring = false,
                            recentRecords = recent,
                            tasks = tasks,
                            anomalies = anomalies,
                            statusMessage = "地图检测超时: 记录仍为 pending\nlocal_url: ${done.record.localUrl}",
                        )
                    }
                },
                onFailure = { error ->
                    current.copy(
                        isInferring = false,
                        recentRecords = recent,
                        tasks = tasks,
                        anomalies = anomalies,
                        statusMessage = "地图检测失败: ${error.message}",
                    )
                },
            )
        }
    }

    override suspend fun createTask(request: CreateTaskRequest): TaskUiModel = withContext(Dispatchers.IO) {
        val task = taskRepository.create(
            name = request.name,
            sceneType = request.sceneType.toModel(),
            areaName = request.areaName,
            priority = request.priority.toModel(),
            operator = request.operator,
        )
        taskRepository.updateStatus(task.id, TaskStatus.MANUAL_REVIEW)
        refreshTaskState(task.id)
        _state.value.activeTask ?: task.toUi()
    }

    override suspend fun listTasks(): List<TaskUiModel> = withContext(Dispatchers.IO) {
        loadTasksWithStats()
    }

    override suspend fun getTask(taskId: String): TaskUiModel? = withContext(Dispatchers.IO) {
        taskRepository.get(taskId)?.toUi(
            imageCount = imageRecordRepository.queryByTaskId(taskId).size,
            anomalyCount = anomalyRepository.countByTask(taskId),
        )
    }

    override fun setActiveTask(taskId: String) {
        scope.launch {
            refreshTaskState(taskId)
        }
    }

    override suspend fun listAnomalies(taskId: String): List<AnomalyUiModel> = withContext(Dispatchers.IO) {
        anomalyRepository.listByTask(taskId).map { enrichAnomalyLocation(it).toUi() }
    }

    override suspend fun submitAnomaly(request: SubmitAnomalyRequest): String = withContext(Dispatchers.IO) {
        val code = request.buildingCode.ifBlank { nextBuildingCode(request.taskId) }
        val inserted = anomalyRepository.insertDraft(
            taskId = request.taskId,
            imageLocalUrl = request.imageLocalUrl,
            bbox = request.bbox.toModel(),
            buildingCode = code,
            source = AnomalyRepository.SOURCE_MANUAL_BOX,
        )
        val record = request.imageLocalUrl?.let { imageRecordRepository.queryByLocalUrl(it) }
        val resolvedLocation = request.location.ifBlank {
            record?.let { resolveAnomalyLocation(it, request.bbox, maskPath = null) }
                ?.takeIf { it.isNotBlank() }
                ?: GeoAnomalyLocationResolver.fallbackPercentLabel(request.bbox)
        }
        val thumbnailPath = record?.let { createThumbnail(inserted.id, it.imgUrl, it.localUrl, request.bbox) }.orEmpty()
        anomalyRepository.updateFields(
            id = inserted.id,
            anomalyType = request.anomalyType.toModel(),
            reviewStatus = ReviewStatus.CONFIRMED,
            location = resolvedLocation,
            comment = request.comment,
            severity = request.severity,
            thumbnailPath = thumbnailPath,
        )
        taskRepository.updateStatus(request.taskId, TaskStatus.MANUAL_REVIEW)
        refreshTaskState(request.taskId)
        selectAnomaly(inserted.id)
        inserted.id
    }

    override suspend fun reviewAnomaly(id: String, request: ReviewAnomalyRequest) {
        withContext(Dispatchers.IO) {
            val updated = anomalyRepository.updateReview(
                id = id,
                status = request.status.toModel(),
                anomalyType = request.anomalyType?.toModel(),
                comment = request.comment,
            )
            updated?.taskId?.let { taskId ->
                taskRepository.updateStatus(taskId, TaskStatus.READY_TO_EXPORT)
                refreshTaskState(taskId)
            }
        }
    }

    override suspend fun updateAnomaly(id: String, fields: AnomalyUpdateRequest) {
        withContext(Dispatchers.IO) {
            val current = anomalyRepository.get(id)
            val location = fields.location?.let { requested ->
                if (current != null && GeoAnomalyLocationResolver.looksLikePercentFallback(requested)) {
                    resolveGeoLocationLabel(current) ?: requested
                } else {
                    requested
                }
            }
            val updated = anomalyRepository.updateFields(
                id = id,
                anomalyType = fields.anomalyType?.toModel(),
                reviewStatus = fields.reviewStatus?.toModel(),
                buildingCode = fields.buildingCode,
                location = location,
                comment = fields.comment,
                severity = fields.severity,
                bbox = fields.bbox?.toModel(),
            )
            updated?.taskId?.let { refreshTaskState(it) }
        }
    }

    override suspend fun attachPhoto(anomalyId: String, uri: Uri) {
        withContext(Dispatchers.IO) {
            val anomaly = anomalyRepository.get(anomalyId) ?: error("异常不存在: $anomalyId")
            val photoDir = File(context.filesDir, "${ModelSpec.ANALYSIS_DIR}/${anomaly.taskId}/photos").apply { mkdirs() }
            val output = File(photoDir, "${anomaly.id}_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(output).use { out -> input.copyTo(out) }
            } ?: error("无法读取照片: $uri")
            anomalyRepository.attachPhoto(anomalyId, output.absolutePath)
            refreshTaskState(anomaly.taskId)
        }
    }

    override suspend fun exportReport(taskId: String, formats: Set<ReportFormat>): ReportExportResult = withContext(Dispatchers.IO) {
        val task = taskRepository.get(taskId) ?: error("任务不存在: $taskId")
        val anomalies = anomalyRepository.listByTask(taskId)
        val records = imageRecordRepository.queryByTaskId(taskId)
        val reportDir = File(context.filesDir, "${ModelSpec.ANALYSIS_DIR}/$taskId/reports").apply { mkdirs() }
        val files = linkedMapOf<ReportFormat, String>()
        formats.forEach { format ->
            when (format) {
                ReportFormat.JSON -> files[format] = writeJsonReport(reportDir, task, anomalies, records)
                ReportFormat.IMAGE -> files[format] = writeImageReport(reportDir, task, anomalies, records)
                ReportFormat.CSV -> files[format] = writeCsvReport(reportDir, anomalies)
                ReportFormat.GEOJSON -> files[format] = writeGeoJsonReport(reportDir, task, anomalies)
                ReportFormat.PDF -> files[format] = writePdfReport(reportDir, task, anomalies)
            }
        }
        val result = ReportExportResult(taskId, System.currentTimeMillis(), files)
        taskRepository.updateStatus(
            taskId,
            if (task.sceneType == SceneType.DISASTER) TaskStatus.PENDING_REPORT else TaskStatus.EXPORTED,
        )
        refreshTaskState(taskId)
        _state.update { it.copy(lastReport = result, statusMessage = "报告已导出：${files.values.joinToString()}") }
        result
    }

    override fun startDisasterTrack() {
        _state.update {
            it.copy(
                disasterTrack = it.disasterTrack.copy(isCollecting = true, isClosed = false),
                statusMessage = "灾害范围采集中，请沿边界行走",
            )
        }
    }

    override fun addDisasterPoint(lat: Double, lng: Double) {
        _state.update {
            it.copy(
                disasterTrack = it.disasterTrack.copy(
                    points = it.disasterTrack.points + DisasterPointUiModel(lat, lng, System.currentTimeMillis()),
                    isClosed = false,
                ),
            )
        }
    }

    override suspend fun captureCurrentLocation() {
        withContext(Dispatchers.Main) {
            if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                updateStatus("需要定位权限才能采集 GPS 点")
                return@withContext
            }
            val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val location = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            if (location == null) {
                updateStatus("暂未获取到定位点，请稍后重试")
            } else {
                addDisasterPoint(location.latitude, location.longitude)
                updateStatus("已记录定位点：${location.latitude}, ${location.longitude}")
            }
        }
    }

    override fun finishDisasterTrack() {
        _state.update {
            val closed = it.disasterTrack.points.size >= 3
            it.copy(
                disasterTrack = it.disasterTrack.copy(isCollecting = false, isClosed = closed),
                statusMessage = if (closed) "实测灾害范围已闭合" else "至少需要 3 个定位点才能闭合范围",
            )
        }
    }

    override fun resetDisasterTrack() {
        _state.update { it.copy(disasterTrack = DisasterTrackUiModel(), statusMessage = "灾害范围已重新采集") }
    }

    override fun setCompareImages(historicalUri: Uri?, currentUri: Uri?) {
        _state.update {
            it.copy(
                compareSession = it.compareSession.copy(
                    historicalImageUri = historicalUri?.toString() ?: it.compareSession.historicalImageUri,
                    currentImageUri = currentUri?.toString() ?: it.compareSession.currentImageUri,
                ),
            )
        }
    }

    override fun setCompareSlider(value: Float) {
        _state.update { it.copy(compareSession = it.compareSession.copy(slider = value.coerceIn(0f, 1f))) }
    }

    override fun refineMaskAt(x: Float, y: Float) {
        _state.update {
            it.copy(
                compareSession = it.compareSession.copy(
                    samStatus = "MobileSAM 模型待算法侧交付；已记录点选坐标 ${"%.2f".format(x)}, ${"%.2f".format(y)}",
                ),
                statusMessage = "MobileSAM 模型待算法侧交付，当前仅保留点选入口",
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

    override fun selectAnomaly(id: String?) {
        _state.update { it.copy(selectedAnomalyId = id) }
    }

    override suspend fun refreshAnomalyLocations() {
        val taskId = _state.value.activeTask?.id ?: return
        withContext(Dispatchers.IO) {
            imageRecordRepository.queryByTaskId(taskId).forEach { record ->
                if (record.status != AnalyseStatus.DONE || record.analyseType != AnalyseType.BUILDING) return@forEach
                val maskPath = SkyEdgeImageAnalyser.maskPathFromSummary(record.summaryJson) ?: return@forEach
                backfillAnomalyLocationsForImage(record, maskPath)
            }
            anomalyRepository.listByTask(taskId).forEach { enrichAnomalyLocation(it) }
            refreshTaskState(taskId)
        }
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

    private fun refreshTasks() {
        scope.launch {
            refreshTaskState(_state.value.activeTask?.id)
        }
    }

    private suspend fun ensureActiveTask(sceneType: SceneType): TaskRecord {
        val active = _state.value.activeTask?.let { taskRepository.get(it.id) }
        if (active != null) return active
        val existing = taskRepository.list().firstOrNull { it.sceneType == sceneType }
        val task = existing ?: taskRepository.create(
            name = if (sceneType == SceneType.DISASTER) "灾害范围核查" else "建筑核查任务",
            sceneType = sceneType,
        )
        taskRepository.updateStatus(task.id, TaskStatus.MANUAL_REVIEW)
        refreshTaskState(task.id)
        return taskRepository.get(task.id) ?: task
    }

    private suspend fun refreshTaskState(activeTaskId: String?) {
        val tasks = loadTasksWithStats()
        val active = activeTaskId?.let { id -> tasks.firstOrNull { it.id == id } } ?: tasks.firstOrNull()
        val anomalies = active?.let { anomalyRepository.listByTask(it.id).map { record -> enrichAnomalyLocation(record).toUi() } }.orEmpty()
        val draft = active?.let { buildReportDraft(it.id) }
        _state.update {
            it.copy(
                tasks = tasks,
                activeTask = active,
                anomalies = anomalies,
                reportDraft = draft,
            )
        }
    }

    private suspend fun loadTasksWithStats(): List<TaskUiModel> =
        taskRepository.list().map { task ->
            task.toUi(
                imageCount = imageRecordRepository.queryByTaskId(task.id).size,
                anomalyCount = anomalyRepository.countByTask(task.id),
            )
        }

    private suspend fun loadActiveAnomalies(): List<AnomalyUiModel> =
        _state.value.activeTask?.let { active ->
            anomalyRepository.listByTask(active.id).map { enrichAnomalyLocation(it).toUi() }
        }.orEmpty()

    private suspend fun createDraftAnomaliesIfNeeded(taskId: String, record: ImageRecord) {
        if (record.status != AnalyseStatus.DONE || record.analyseType != AnalyseType.BUILDING) return
        val maskPath = SkyEdgeImageAnalyser.maskPathFromSummary(record.summaryJson) ?: return
        if (anomalyRepository.listByImage(record.localUrl).isNotEmpty()) {
            backfillAnomalyLocationsForImage(record, maskPath)
            return
        }
        val instances = MaskInstanceExtractor.extract(maskPath)
            .sortedByDescending { it.pixelArea }
            .take(MAX_AUTO_ANOMALIES)
        val start = anomalyRepository.countByTask(taskId) + 1
        instances.forEachIndexed { index, instance ->
            val inserted = anomalyRepository.insertDraft(
                taskId = taskId,
                imageLocalUrl = record.localUrl,
                bbox = instance.bbox.toModel(),
                buildingCode = "B-${(start + index).toString().padStart(3, '0')}",
                location = resolveAnomalyLocation(record, instance.bbox, maskPath),
            )
            val thumbnailPath = createThumbnail(inserted.id, record.imgUrl, record.localUrl, instance.bbox)
            if (thumbnailPath.isNotBlank()) {
                anomalyRepository.updateFields(inserted.id, thumbnailPath = thumbnailPath)
            }
        }
        if (instances.isNotEmpty()) {
            taskRepository.updateStatus(taskId, TaskStatus.MANUAL_REVIEW)
        }
    }

    /** Re-resolve percent-only locations when geo.json is present (e.g. after GeoTIFF import or app upgrade). */
    private suspend fun backfillAnomalyLocationsForImage(record: ImageRecord, maskPath: String) {
        val sessionDir = File(record.localUrl.trimEnd(File.separatorChar))
        if (!GeoJsonIO.geoFile(sessionDir).exists()) return
        anomalyRepository.listByImage(record.localUrl).forEach { anomaly ->
            if (!GeoAnomalyLocationResolver.looksLikePercentFallback(anomaly.location)) return@forEach
            val resolved = resolveAnomalyLocation(record, anomaly.bbox.toUi(), maskPath)
            if (!GeoAnomalyLocationResolver.looksLikePercentFallback(resolved)) {
                anomalyRepository.updateFields(anomaly.id, location = resolved)
            }
        }
    }

    private fun createThumbnail(
        anomalyId: String,
        imageUri: String,
        localUrl: String,
        bbox: BoundingBoxDto,
    ): String {
        val output = File(localUrl.trimEnd(File.separatorChar), "thumbs/$anomalyId.jpg")
        return runCatching {
            AnomalyThumbnailGenerator.generate(context, imageUri, bbox, output)
        }.getOrDefault("")
    }

    private suspend fun nextBuildingCode(taskId: String): String =
        "B-${(anomalyRepository.countByTask(taskId) + 1).toString().padStart(3, '0')}"

    private suspend fun buildReportDraft(taskId: String): ReportDraftUiModel {
        val anomalies = anomalyRepository.listByTask(taskId)
        val confirmed = anomalies.count { it.reviewStatus == ReviewStatus.CONFIRMED }
        val rejected = anomalies.count { it.reviewStatus == ReviewStatus.REJECTED }
        val counts = anomalies
            .groupingBy { it.anomalyType.toUi() }
            .eachCount()
        return ReportDraftUiModel(
            taskId = taskId,
            objectCount = anomalies.size,
            confirmedCount = confirmed,
            rejectedCount = rejected,
            typeCounts = counts,
            anomalies = anomalies.map { it.toUi() },
        )
    }

    private fun writeJsonReport(
        reportDir: File,
        task: TaskRecord,
        anomalies: List<AnomalyRecord>,
        records: List<ImageRecord>,
    ): String {
        val output = File(reportDir, "report.json")
        val json = JSONObject()
            .put("task", task.toJson())
            .put("generated_at", System.currentTimeMillis())
            .put("records", JSONArray().also { array ->
                records.forEach { record ->
                    array.put(
                        JSONObject()
                            .put("local_url", record.localUrl)
                            .put("img_url", record.imgUrl)
                            .put("analyse_type", record.analyseType.name.lowercase())
                            .put("status", record.status.name.lowercase())
                            .put("summary", runCatching { JSONObject(record.summaryJson) }.getOrElse { JSONObject() }),
                    )
                }
            })
            .put("anomalies", anomalies.toJsonArray())
            .put("disaster_track", disasterTrackToJson(_state.value.disasterTrack))
        output.writeText(json.toString(2))
        return output.absolutePath
    }

    private fun writeCsvReport(reportDir: File, anomalies: List<AnomalyRecord>): String {
        val output = File(reportDir, "anomalies.csv")
        output.writeText(
            buildString {
                appendLine("id,building_code,type,status,location,severity,comment")
                anomalies.forEach {
                    appendLine(
                        listOf(
                            it.id,
                            it.buildingCode,
                            it.anomalyType.value,
                            it.reviewStatus.value,
                            it.location,
                            it.severity,
                            it.comment,
                        ).joinToString(",") { cell -> "\"${cell.replace("\"", "\"\"")}\"" },
                    )
                }
            },
        )
        return output.absolutePath
    }

    private fun writeGeoJsonReport(reportDir: File, task: TaskRecord, anomalies: List<AnomalyRecord>): String {
        val output = File(reportDir, "report.geojson")
        val features = JSONArray()
        anomalies.forEach { anomaly ->
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put(
                        "properties",
                        JSONObject()
                            .put("id", anomaly.id)
                            .put("task_id", task.id)
                            .put("building_code", anomaly.buildingCode)
                            .put("anomaly_type", anomaly.anomalyType.value)
                            .put("review_status", anomaly.reviewStatus.value),
                    )
                    .put("geometry", bboxToGeoJsonGeometry(anomaly.bbox)),
            )
        }
        val track = _state.value.disasterTrack
        if (track.points.size >= 3) {
            features.put(
                JSONObject()
                    .put("type", "Feature")
                    .put("properties", JSONObject().put("kind", "disaster_track"))
                    .put("geometry", disasterTrackGeometry(track.points)),
            )
        }
        output.writeText(
            JSONObject()
                .put("type", "FeatureCollection")
                .put("features", features)
                .toString(2),
        )
        return output.absolutePath
    }

    private fun writeImageReport(
        reportDir: File,
        task: TaskRecord,
        anomalies: List<AnomalyRecord>,
        records: List<ImageRecord>,
    ): String {
        val output = File(reportDir, "report.png")
        return ReportImageComposer.compose(context, output, task, anomalies, records)
    }

    private fun writePdfReport(reportDir: File, task: TaskRecord, anomalies: List<AnomalyRecord>): String {
        val output = File(reportDir, "report.pdf")
        val document = PdfDocument()
        val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create())
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.textSize = 22f
        paint.color = Color.rgb(27, 94, 32)
        page.canvas.drawText(task.name, 40f, 60f, paint)
        paint.textSize = 14f
        paint.color = Color.DKGRAY
        var y = 100f
        page.canvas.drawText("建筑对象：${anomalies.size}", 40f, y, paint)
        y += 28f
        anomalies.take(18).forEach {
            page.canvas.drawText("${it.buildingCode}  ${it.anomalyType.value}  ${it.reviewStatus.value}", 40f, y, paint)
            y += 24f
        }
        document.finishPage(page)
        FileOutputStream(output).use(document::writeTo)
        document.close()
        return output.absolutePath
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
            sourceUri = imgUrl,
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

    private fun TaskRecord.toUi(imageCount: Int = 0, anomalyCount: Int = 0): TaskUiModel =
        TaskUiModel(
            id = id,
            name = name,
            sceneType = sceneType.toUi(),
            areaName = areaName,
            status = status.toUi(),
            priority = priority.toUi(),
            operator = operator,
            createdAt = createdAt,
            updatedAt = updatedAt,
            imageCount = imageCount,
            anomalyCount = anomalyCount,
        )

    private fun AnomalyRecord.toUi(): AnomalyUiModel =
        AnomalyUiModel(
            id = id,
            taskId = taskId,
            imageLocalUrl = imageLocalUrl,
            bbox = bbox.toUi(),
            anomalyType = anomalyType.toUi(),
            reviewStatus = reviewStatus.toUi(),
            buildingCode = buildingCode,
            location = location,
            source = source,
            comment = comment,
            severity = severity,
            thumbnailPath = thumbnailPath,
            photoPaths = photoPaths,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    private fun SceneTypeUi.toModel(): SceneType = when (this) {
        SceneTypeUi.BUILDING -> SceneType.BUILDING
        SceneTypeUi.DISASTER -> SceneType.DISASTER
    }

    private fun SceneType.toUi(): SceneTypeUi = when (this) {
        SceneType.BUILDING -> SceneTypeUi.BUILDING
        SceneType.DISASTER -> SceneTypeUi.DISASTER
    }

    private fun TaskStatus.toUi(): TaskStatusUi = when (this) {
        TaskStatus.DRAFT -> TaskStatusUi.DRAFT
        TaskStatus.MANUAL_REVIEW -> TaskStatusUi.MANUAL_REVIEW
        TaskStatus.READY_TO_EXPORT -> TaskStatusUi.READY_TO_EXPORT
        TaskStatus.EXPORTED -> TaskStatusUi.EXPORTED
        TaskStatus.PENDING_REPORT -> TaskStatusUi.PENDING_REPORT
    }

    private fun TaskPriorityUi.toModel(): TaskPriority = when (this) {
        TaskPriorityUi.LOW -> TaskPriority.LOW
        TaskPriorityUi.NORMAL -> TaskPriority.NORMAL
        TaskPriorityUi.HIGH -> TaskPriority.HIGH
    }

    private fun TaskPriority.toUi(): TaskPriorityUi = when (this) {
        TaskPriority.LOW -> TaskPriorityUi.LOW
        TaskPriority.NORMAL -> TaskPriorityUi.NORMAL
        TaskPriority.HIGH -> TaskPriorityUi.HIGH
    }

    private fun AnomalyTypeUi.toModel(): AnomalyType = when (this) {
        AnomalyTypeUi.NEW_BUILDING -> AnomalyType.NEW_BUILDING
        AnomalyTypeUi.SUSPECTED_ILLEGAL -> AnomalyType.SUSPECTED_ILLEGAL
        AnomalyTypeUi.TEMPORARY_STRUCTURE -> AnomalyType.TEMPORARY_STRUCTURE
        AnomalyTypeUi.DAMAGED_COLLAPSED -> AnomalyType.DAMAGED_COLLAPSED
        AnomalyTypeUi.DEBRIS -> AnomalyType.DEBRIS
        AnomalyTypeUi.LANDSLIDE -> AnomalyType.LANDSLIDE
        AnomalyTypeUi.OTHER -> AnomalyType.OTHER
    }

    private fun AnomalyType.toUi(): AnomalyTypeUi = when (this) {
        AnomalyType.NEW_BUILDING -> AnomalyTypeUi.NEW_BUILDING
        AnomalyType.SUSPECTED_ILLEGAL -> AnomalyTypeUi.SUSPECTED_ILLEGAL
        AnomalyType.TEMPORARY_STRUCTURE -> AnomalyTypeUi.TEMPORARY_STRUCTURE
        AnomalyType.DAMAGED_COLLAPSED -> AnomalyTypeUi.DAMAGED_COLLAPSED
        AnomalyType.DEBRIS -> AnomalyTypeUi.DEBRIS
        AnomalyType.LANDSLIDE -> AnomalyTypeUi.LANDSLIDE
        AnomalyType.OTHER -> AnomalyTypeUi.OTHER
    }

    private fun ReviewStatusUi.toModel(): ReviewStatus = when (this) {
        ReviewStatusUi.PENDING -> ReviewStatus.PENDING
        ReviewStatusUi.CONFIRMED -> ReviewStatus.CONFIRMED
        ReviewStatusUi.VERIFIED -> ReviewStatus.CONFIRMED
        ReviewStatusUi.REJECTED -> ReviewStatus.REJECTED
    }

    private fun ReviewStatus.toUi(): ReviewStatusUi = when (this) {
        ReviewStatus.PENDING -> ReviewStatusUi.PENDING
        ReviewStatus.CONFIRMED -> ReviewStatusUi.CONFIRMED
        ReviewStatus.REJECTED -> ReviewStatusUi.REJECTED
    }

    private fun BoundingBoxDto.toModel(): BoundingBoxRecord =
        BoundingBoxRecord(x, y, width, height)

    private fun resolveAnomalyLocation(
        record: ImageRecord,
        bbox: BoundingBoxDto,
        maskPath: String?,
    ): String {
        val sessionDir = File(record.localUrl.trimEnd(File.separatorChar))
        val metadata = GeoJsonIO.read(GeoJsonIO.geoFile(sessionDir)) ?: return bbox.autoLocationLabel()
        val resolvedMaskPath = maskPath?.takeIf { it.isNotBlank() }
            ?: SkyEdgeImageAnalyser.maskPathFromSummary(record.summaryJson)
            ?: return GeoAnomalyLocationResolver.resolve(
                metadata = metadata,
                bbox = bbox,
                maskWidth = metadata.previewWidth,
                maskHeight = metadata.previewHeight,
            )
        val maskSize = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(resolvedMaskPath, maskSize)
        val maskWidth = maskSize.outWidth
        val maskHeight = maskSize.outHeight
        if (maskWidth <= 0 || maskHeight <= 0) {
            return GeoAnomalyLocationResolver.resolve(
                metadata = metadata,
                bbox = bbox,
                maskWidth = metadata.previewWidth,
                maskHeight = metadata.previewHeight,
            )
        }
        return GeoAnomalyLocationResolver.resolve(metadata, bbox, maskWidth, maskHeight)
    }

    /** 内置样例/普通照片无 GPS 时，用画面百分比描述候选位置。 */
    private fun BoundingBoxDto.autoLocationLabel(): String =
        GeoAnomalyLocationResolver.fallbackPercentLabel(this)

    private suspend fun enrichAnomalyLocation(record: AnomalyRecord): AnomalyRecord {
        if (!GeoAnomalyLocationResolver.looksLikePercentFallback(record.location)) return record
        val resolved = resolveGeoLocationLabel(record) ?: return record
        anomalyRepository.updateFields(record.id, location = resolved)
        return record.copy(location = resolved)
    }

    private suspend fun resolveGeoLocationLabel(record: AnomalyRecord): String? {
        val imageLocalUrl = record.imageLocalUrl?.takeIf { it.isNotBlank() } ?: return null
        val imageRecord = imageRecordRepository.queryByLocalUrl(imageLocalUrl) ?: return null
        val maskPath = SkyEdgeImageAnalyser.maskPathFromSummary(imageRecord.summaryJson)
        val resolved = resolveAnomalyLocation(imageRecord, record.bbox.toUi(), maskPath)
        return resolved.takeUnless { GeoAnomalyLocationResolver.looksLikePercentFallback(it) }
    }

    private fun BoundingBoxRecord.toUi(): BoundingBoxDto =
        BoundingBoxDto(x, y, width, height)

    private fun TaskRecord.toJson(): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("scene_type", sceneType.value)
            .put("area_name", areaName)
            .put("status", status.value)
            .put("priority", priority.value)
            .put("operator", operator)
            .put("created_at", createdAt)
            .put("updated_at", updatedAt)

    private fun List<AnomalyRecord>.toJsonArray(): JSONArray =
        JSONArray().also { array ->
            forEach { anomaly ->
                array.put(
                    JSONObject()
                        .put("id", anomaly.id)
                        .put("task_id", anomaly.taskId)
                        .put("image_local_url", anomaly.imageLocalUrl)
                        .put("bbox", anomaly.bbox.toJson())
                        .put("anomaly_type", anomaly.anomalyType.value)
                        .put("review_status", anomaly.reviewStatus.value)
                        .put("building_code", anomaly.buildingCode)
                        .put("location", anomaly.location)
                        .put("source", anomaly.source)
                        .put("comment", anomaly.comment)
                        .put("severity", anomaly.severity)
                        .put("thumbnail_path", anomaly.thumbnailPath)
                        .put("photo_paths", JSONArray(anomaly.photoPaths)),
                )
            }
        }

    private fun BoundingBoxRecord.toJson(): JSONObject =
        JSONObject()
            .put("x", x.toDouble())
            .put("y", y.toDouble())
            .put("width", width.toDouble())
            .put("height", height.toDouble())

    private fun bboxToGeoJsonGeometry(bbox: BoundingBoxRecord): JSONObject {
        val x2 = bbox.x + bbox.width
        val y2 = bbox.y + bbox.height
        return JSONObject()
            .put("type", "Polygon")
            .put(
                "coordinates",
                JSONArray().put(
                    JSONArray()
                        .put(JSONArray().put(bbox.x).put(bbox.y))
                        .put(JSONArray().put(x2).put(bbox.y))
                        .put(JSONArray().put(x2).put(y2))
                        .put(JSONArray().put(bbox.x).put(y2))
                        .put(JSONArray().put(bbox.x).put(bbox.y)),
                ),
            )
    }

    private fun disasterTrackGeometry(points: List<DisasterPointUiModel>): JSONObject {
        val ring = JSONArray()
        points.forEach { ring.put(JSONArray().put(it.lng).put(it.lat)) }
        points.firstOrNull()?.let { ring.put(JSONArray().put(it.lng).put(it.lat)) }
        return JSONObject().put("type", "Polygon").put("coordinates", JSONArray().put(ring))
    }

    private fun disasterTrackToJson(track: DisasterTrackUiModel): JSONObject =
        JSONObject()
            .put("is_collecting", track.isCollecting)
            .put("is_closed", track.isClosed)
            .put("risk_level", track.riskLevel)
            .put("disaster_type", track.disasterType)
            .put("affected_objects", track.affectedObjects)
            .put("description", track.description)
            .put(
                "points",
                JSONArray().also { array ->
                    track.points.forEach { point ->
                        array.put(
                            JSONObject()
                                .put("lat", point.lat)
                                .put("lng", point.lng)
                                .put("time", point.time),
                        )
                    }
                },
            )

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
        private const val MAX_AUTO_ANOMALIES = 50
        private const val INTERACTIVE_SAM_TIMEOUT_MS = 90_000L
    }
}

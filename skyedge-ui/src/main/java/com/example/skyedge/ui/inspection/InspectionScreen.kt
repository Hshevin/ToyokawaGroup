package com.example.skyedge.ui.inspection

import android.graphics.Bitmap
import android.net.Uri
import com.example.skyedge.core.api.InteractivePoint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.skyedge.ui.theme.SkyEdgeColors
import com.example.skyedge.ui.theme.SkyPrimaryButton
import com.example.skyedge.ui.theme.SkyScreenHeader
import com.example.skyedge.ui.theme.SkySecondaryButton

@Composable
fun InspectionScreen(
    viewModel: InferenceViewModel,
    selectedImageUri: Uri?,
    onPickImage: () -> Unit,
    embedded: Boolean = false,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val correctionEnabled = uiState.interactiveImageReady && selectedImageUri != null
    val preparingCorrection = uiState.isInferring && uiState.lastMaskPath != null && !correctionEnabled

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .then(if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize())
            .then(if (!embedded) Modifier.verticalScroll(scrollState) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (!embedded) {
            SkyScreenHeader(
                eyebrow = "边缘巡检",
                title = "低空巡检",
            )
        }

        Column(
            modifier = Modifier.padding(if (embedded) 0.dp else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (!embedded) {
                Text(
                    text = "Building 分割 + 点选/框选局部修正",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SkyEdgeColors.Muted,
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            CorrectionStatusBanner(
                hasImage = selectedImageUri != null,
                hasMask = uiState.lastMaskPath != null,
                preparingCorrection = preparingCorrection,
                correctionEnabled = correctionEnabled,
                roiActive = uiState.interactiveRoiActive,
            )

            Spacer(modifier = Modifier.height(12.dp))

            val maskPath = uiState.lastMaskPath
            val overlayBitmap = remember(maskPath, uiState.maskUpdateSeq) {
                buildMaskOverlay(maskPath, uiState.selectedModelKey)
            }
            if (selectedImageUri != null || overlayBitmap != null) {
                val imageWidth = uiState.interactiveImageWidth ?: 1
                val imageHeight = uiState.interactiveImageHeight ?: 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.Top,
                ) {
                    if (selectedImageUri != null) {
                        InteractiveImagePanel(
                            title = when {
                                correctionEnabled -> "原图（可点选/框选）"
                                preparingCorrection -> "原图（修正引擎准备中…）"
                                uiState.lastMaskPath != null -> "原图（等待修正就绪）"
                                else -> "原图"
                            },
                            imageUri = selectedImageUri,
                            overlayBitmap = null,
                            correctionEnabled = correctionEnabled,
                            imageWidth = imageWidth,
                            imageHeight = imageHeight,
                            interactivePoints = uiState.interactivePoints,
                            onTap = { x, y ->
                                viewModel.inferInteractivePoint(x, y, imageWidth, imageHeight)
                            },
                            onBox = { x1, y1, x2, y2 ->
                                viewModel.selectCorrectionRoi(
                                    uri = selectedImageUri,
                                    x1 = x1,
                                    y1 = y1,
                                    x2 = x2,
                                    y2 = y2,
                                    imageWidth = imageWidth,
                                    imageHeight = imageHeight,
                                )
                            },
                        )
                    }
                    InteractiveImagePanel(
                        title = if (correctionEnabled) "对比（可点选/框选）" else "处理后对比",
                        imageUri = selectedImageUri,
                        overlayBitmap = overlayBitmap,
                        correctionEnabled = correctionEnabled && selectedImageUri != null,
                        imageWidth = imageWidth,
                        imageHeight = imageHeight,
                        interactivePoints = uiState.interactivePoints,
                        onTap = { x, y ->
                            viewModel.inferInteractivePoint(x, y, imageWidth, imageHeight)
                        },
                        onBox = { x1, y1, x2, y2 ->
                            selectedImageUri?.let { uri ->
                                viewModel.selectCorrectionRoi(
                                    uri = uri,
                                    x1 = x1,
                                    y1 = y1,
                                    x2 = x2,
                                    y2 = y2,
                                    imageWidth = imageWidth,
                                    imageHeight = imageHeight,
                                )
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            if (uiState.isLoadingModel || uiState.isInferring) {
                CircularProgressIndicator(color = SkyEdgeColors.Green)
                Spacer(modifier = Modifier.height(12.dp))
            }

            if (!embedded) {
                SkyPrimaryButton(
                    text = when {
                        uiState.isLoadingModel -> "模型加载中"
                        !uiState.isModelReady -> "模型未就绪"
                        uiState.isInferring -> "处理中"
                        else -> "导入现场照片"
                    },
                    onClick = onPickImage,
                    enabled = uiState.isModelReady && !uiState.isInferring,
                )
                Spacer(modifier = Modifier.height(12.dp))
                SkySecondaryButton(
                    text = "Building 修正演示（无需选图）",
                    onClick = { viewModel.runMobileSamDemo("building_demo") },
                    enabled = uiState.isModelReady && !uiState.isInferring,
                )
                Spacer(modifier = Modifier.height(20.dp))
            }
            Text(
                text = uiState.statusMessage,
                style = MaterialTheme.typography.bodySmall,
                color = SkyEdgeColors.Muted,
            )

            if (!embedded && uiState.recentRecords.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "本地检测记录（Room）",
                    style = MaterialTheme.typography.titleSmall,
                    color = SkyEdgeColors.Ink,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 180.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    uiState.recentRecords.forEach { item ->
                        Text(
                            text = buildString {
                                append("[${item.status}] ${item.analyseType}")
                                if (item.detail.isNotBlank()) append(" · ${item.detail}")
                                append("\n")
                                append(item.localUrl)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = SkyEdgeColors.Muted,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun InteractiveImagePanel(
    title: String,
    imageUri: Uri?,
    overlayBitmap: Bitmap?,
    correctionEnabled: Boolean,
    imageWidth: Int,
    imageHeight: Int,
    interactivePoints: List<InteractivePoint>,
    onTap: (x: Float, y: Float) -> Unit,
    onBox: (x1: Float, y1: Float, x2: Float, y2: Float) -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.labelMedium, color = SkyEdgeColors.Muted)
        Spacer(modifier = Modifier.height(6.dp))
        var viewSize by remember { mutableStateOf(IntSize.Zero) }
        var dragStart by remember { mutableStateOf<Offset?>(null) }
        var dragEnd by remember { mutableStateOf<Offset?>(null) }
        Box(
            modifier = Modifier
                .size(170.dp)
                .onSizeChanged { viewSize = it }
                .correctionGestures(
                    enabled = correctionEnabled && imageWidth > 1 && imageHeight > 1,
                    viewSize = viewSize,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    onTap = onTap,
                    onBox = onBox,
                    onDragPreview = { start, end ->
                        dragStart = start
                        dragEnd = end
                    },
                ),
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            if (overlayBitmap != null) {
                Image(
                    bitmap = overlayBitmap.asImageBitmap(),
                    contentDescription = "Mask Overlay",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            if (correctionEnabled && viewSize.width > 0) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    interactivePoints.forEach { point ->
                        InspectionImageMapper
                            .mapToViewOffset(point.x, point.y, viewSize, imageWidth, imageHeight)
                            ?.let { center ->
                                drawCircle(
                                    color = Color.White,
                                    radius = 6.dp.toPx(),
                                    center = center,
                                )
                                drawCircle(
                                    color = Color.Black,
                                    radius = 6.dp.toPx(),
                                    center = center,
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                            }
                    }
                    val start = dragStart
                    val end = dragEnd
                    if (start != null && end != null) {
                        drawRect(
                            color = SkyEdgeColors.Cyan.copy(alpha = 0.8f),
                            topLeft = Offset(minOf(start.x, end.x), minOf(start.y, end.y)),
                            size = androidx.compose.ui.geometry.Size(
                                kotlin.math.abs(end.x - start.x),
                                kotlin.math.abs(end.y - start.y),
                            ),
                            style = Stroke(width = 2.dp.toPx()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CorrectionStatusBanner(
    hasImage: Boolean,
    hasMask: Boolean,
    preparingCorrection: Boolean,
    correctionEnabled: Boolean,
    roiActive: Boolean,
) {
    val (containerColor, textColor, text) = when {
        correctionEnabled -> Triple(
            SkyEdgeColors.Field,
            SkyEdgeColors.GreenDark,
            buildString {
                append("✓ 可交互：单击=SAM 补漏（叠加）；框选=SAM 框内补漏（叠加）")
                if (roiActive) append("（已按 Building 检测裁剪 ROI）")
            },
        )
        preparingCorrection -> Triple(
            Color(0xFFF8F1E4),
            SkyEdgeColors.Amber,
            "⏳ Building 已完成，正在编码修正区域（SAM 已在后台预热）…",
        )
        hasMask && hasImage -> Triple(
            Color(0xFFF8F1E4),
            SkyEdgeColors.Amber,
            "⏳ 等待修正引擎就绪，请看下方状态栏是否出现「局部修正已就绪」",
        )
        hasImage -> Triple(
            Color(0xFFE8F3F5),
            SkyEdgeColors.Cyan,
            "① 导入后自动 Building 检测 → ② 等待修正就绪 → ③ 点选/框选",
        )
        else -> Triple(
            SkyEdgeColors.Paper,
            SkyEdgeColors.Muted,
            "请先导入现场照片",
        )
    }
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = textColor,
        )
    }
}

package com.example.skyedge.ui.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.skyedge.core.geo.GeoAnomalyLocationResolver
import com.example.skyedge.core.api.AnomalyTypeUi
import com.example.skyedge.core.api.AnomalyUiModel
import com.example.skyedge.core.api.AnomalyUpdateRequest
import com.example.skyedge.core.api.BoundingBoxDto
import com.example.skyedge.core.api.ReviewAnomalyRequest
import com.example.skyedge.core.api.ReviewStatusUi
import com.example.skyedge.core.api.SubmitAnomalyRequest
import com.example.skyedge.ui.inspection.InferenceViewModel
import com.example.skyedge.ui.inspection.buildMaskOverlay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 建筑标注页（场景一 · 人工核查）
 *
 * 视觉对齐 docs/goals/示例渲染图04.png：
 *  - 顶部：场景小标签 + 标题「建筑标注」 + 右上「人工核查」胶囊
 *  - 中部：全宽影像，绿 mask + 黄色 bbox 描边 + 黄色定位针
 *  - 拖拽 handle：分页指示条
 *  - 「建筑详情」白色卡片：标题栏右侧保存校正 / 取消校正
 *  - 编号 / 位置 / 疑似图斑位置 / 形式 / 人工复核 / 复核时间
 *
 * 不依赖场景二（灾害范围），由 [ReviewScreen] 调用方传入。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    viewModel: InferenceViewModel,
    onTakePhoto: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val activeTask = uiState.activeTask
    val anomalies = uiState.anomalies
    val selected = anomalies.firstOrNull { it.id == uiState.selectedAnomalyId }
        ?: anomalies.firstOrNull()
    val activeRecord = remember(uiState.recentRecords, selected?.imageLocalUrl) {
        selected?.imageLocalUrl?.let { localUrl ->
            uiState.recentRecords.firstOrNull { it.localUrl == localUrl }
        } ?: uiState.recentRecords.firstOrNull()
    }
    val visibleAnomalies = remember(anomalies, activeRecord?.localUrl) {
        val imageUrl = activeRecord?.localUrl
        if (imageUrl.isNullOrBlank()) anomalies
        else anomalies.filter { it.imageLocalUrl == imageUrl || it.imageLocalUrl.isNullOrBlank() }
    }

    LaunchedEffect(activeTask?.id) {
        viewModel.refreshAnomalyLocations()
    }
    val overlayBitmap = remember(uiState.lastMaskPath, uiState.selectedModelKey) {
        buildMaskOverlay(uiState.lastMaskPath, uiState.selectedModelKey)
    }

    // 若已选中或首次进入，自动选中第一栋，保证详情卡可用
    LaunchedEffect(anomalies, uiState.selectedAnomalyId) {
        if (uiState.selectedAnomalyId == null && anomalies.isNotEmpty()) {
            viewModel.selectAnomaly(anomalies.first().id)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // 顶部：场景小标签 + 标题 + 右上「人工核查」胶囊
        ReviewHeader(activeTaskName = activeTask?.name, sceneLabel = "城市建筑场景")

        // 影像区：原图 + 绿 mask + 黄色 bbox + 黄色定位针
        AnnotationImageCanvas(
            imageUri = activeRecord?.sourceUri,
            overlayBitmap = overlayBitmap,
            anomalies = visibleAnomalies,
            selectedId = selected?.id,
            dragStart = dragStart,
            dragEnd = dragEnd,
            onTapAnomaly = { viewModel.selectAnomaly(it.id) },
            onDragStart = { dragStart = it; dragEnd = it },
            onDragUpdate = { dragEnd = it },
            onDragEnd = { start, end, viewW, viewH ->
                val task = activeTask
                if (start != null && end != null && task != null && viewW > 0f && viewH > 0f) {
                    val w = abs(end.x - start.x)
                    val h = abs(end.y - start.y)
                    if (w > 24f && h > 24f) {
                        viewModel.submitAnomaly(
                            SubmitAnomalyRequest(
                                taskId = task.id,
                                imageLocalUrl = activeRecord?.localUrl,
                                bbox = BoundingBoxDto(
                                    x = min(start.x, end.x) / viewW,
                                    y = min(start.y, end.y) / viewH,
                                    width = w / viewW,
                                    height = h / viewH,
                                ),
                                anomalyType = AnomalyTypeUi.SUSPECTED_ILLEGAL,
                            ),
                        )
                    }
                }
                dragStart = null
                dragEnd = null
            },
        )

        // 拖拽 handle：分页指示条
        PagerIndicator(
            total = anomalies.size.coerceAtLeast(1),
            current = anomalies.indexOfFirst { it.id == selected?.id }
                .let { if (it < 0) 0 else it },
        )

        // 「建筑详情」卡片
        if (selected != null) {
            AnnotationDetailCard(
                anomaly = selected,
                onUpdate = { viewModel.updateAnomaly(selected.id, it) },
                onSaveReview = { type, comment, draftStatus ->
                    val request = when (draftStatus) {
                        ReviewStatusUi.REJECTED -> ReviewAnomalyRequest(
                            ReviewStatusUi.REJECTED,
                            type,
                            comment.ifBlank { "核验有误" },
                        )
                        ReviewStatusUi.VERIFIED -> ReviewAnomalyRequest(
                            ReviewStatusUi.VERIFIED,
                            type,
                            comment,
                        )
                        ReviewStatusUi.CONFIRMED -> ReviewAnomalyRequest(
                            ReviewStatusUi.CONFIRMED,
                            type,
                            comment,
                        )
                        ReviewStatusUi.PENDING -> ReviewAnomalyRequest(
                            ReviewStatusUi.CONFIRMED,
                            type,
                            comment,
                        )
                    }
                    viewModel.reviewAnomaly(selected.id, request)
                },
                onTakePhoto = { onTakePhoto(selected.id) },
                onCancel = { viewModel.selectAnomaly(null) },
            )
        } else {
            EmptyAnnotationCard(
                onPickAnomaly = {
                    anomalies.firstOrNull()?.let { viewModel.selectAnomaly(it.id) }
                },
            )
        }

        // 候选建筑列表（横向缩略图，便于左右切换）
        if (anomalies.size > 1) {
            Text(
                text = "候选建筑（${anomalies.size}）",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(anomalies, key = { it.id }) { anomaly ->
                    AnomalyChip(
                        anomaly = anomaly,
                        isSelected = anomaly.id == selected?.id,
                        onClick = { viewModel.selectAnomaly(anomaly.id) },
                    )
                }
            }
        }

        Text(
            text = if (anomalies.isEmpty()) {
                "暂无候选，请先在影像页导入并完成检测。"
            } else {
                "点击影像中的建筑框或下方候选卡切换；拖拽影像可新增框选。"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReviewHeader(activeTaskName: String?, sceneLabel: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = sceneLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "建筑标注",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (!activeTaskName.isNullOrBlank()) {
                Text(
                    text = activeTaskName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StatusPill(text = "人工核查")
    }
}

@Composable
private fun StatusPill(text: String) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color(0xFFE8F5E9),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF66BB6A)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(Color(0xFF2E7D32), shape = CircleShape),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF2E7D32),
            )
        }
    }
}

@Composable
private fun AnnotationImageCanvas(
    imageUri: String?,
    overlayBitmap: android.graphics.Bitmap?,
    anomalies: List<AnomalyUiModel>,
    selectedId: String?,
    dragStart: Offset?,
    dragEnd: Offset?,
    onTapAnomaly: (AnomalyUiModel) -> Unit,
    onDragStart: (Offset) -> Unit,
    onDragUpdate: (Offset) -> Unit,
    onDragEnd: (Offset?, Offset?, Float, Float) -> Unit,
) {
    var viewSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 220.dp, max = 320.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { viewSize = it }
                .pointerInput(anomalies) {
                    detectTapGestures { offset ->
                        val hit = hitTestAnomaly(
                            offset,
                            size.width.toFloat(),
                            size.height.toFloat(),
                            anomalies,
                        )
                        if (hit != null) onTapAnomaly(hit)
                    }
                }
                .pointerInput(anomalies) {
                    val viewW = size.width.toFloat()
                    val viewH = size.height.toFloat()
                    detectDragGestures(
                        onDragStart = { offset -> onDragStart(offset) },
                        onDrag = { change, _ -> onDragUpdate(change.position) },
                        onDragEnd = { onDragEnd(dragStart, dragEnd, viewW, viewH) },
                        onDragCancel = { onDragEnd(null, null, viewW, viewH) },
                    )
                },
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = "核查底图",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            overlayBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "mask 叠加",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
            Canvas(Modifier.fillMaxSize()) {
                anomalies.forEach { anomaly ->
                    val color = if (anomaly.id == selectedId) {
                        Color(0xFFFFC107)
                    } else {
                        Color(0xFFFFEB3B)
                    }
                    drawRect(
                        color = color,
                        topLeft = Offset(anomaly.bbox.x * size.width, anomaly.bbox.y * size.height),
                        size = Size(anomaly.bbox.width * size.width, anomaly.bbox.height * size.height),
                        style = Stroke(width = if (anomaly.id == selectedId) 6f else 4f),
                    )
                }
                // 黄色定位针：选中建筑的中心点
                val center = anomalies.firstOrNull { it.id == selectedId }
                    ?.let { anom ->
                        Offset(
                            (anom.bbox.x + anom.bbox.width / 2f) * size.width,
                            (anom.bbox.y + anom.bbox.height / 2f) * size.height,
                        )
                    }
                center?.let { drawLocationPin(it) }
                val s = dragStart
                val e = dragEnd
                if (s != null && e != null) {
                    drawRect(
                        color = Color(0xFFFF9800),
                        topLeft = Offset(min(s.x, e.x), min(s.y, e.y)),
                        size = Size(max(1f, abs(e.x - s.x)), max(1f, abs(e.y - s.y))),
                        style = Stroke(width = 4f),
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLocationPin(center: Offset) {
    val radius = 14f
    val pinColor = Color(0xFFFFC107)
    // 外圈光晕
    drawCircle(
        color = Color(0x66FFC107),
        radius = radius + 8f,
        center = center,
    )
    // 内圈
    drawCircle(
        color = pinColor,
        radius = radius,
        center = center,
    )
    drawCircle(
        color = Color.White,
        radius = radius / 2.5f,
        center = center,
    )
    // 向下小三角
    val path = Path().apply {
        fillType = PathFillType.NonZero
        moveTo(center.x - 6f, center.y + radius - 2f)
        lineTo(center.x + 6f, center.y + radius - 2f)
        lineTo(center.x, center.y + radius + 10f)
        close()
    }
    drawPath(path = path, color = pinColor)
}

@Composable
private fun PagerIndicator(total: Int, current: Int) {
    if (total <= 1) {
        // 单条建筑时显示居中的拖拽 handle
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(46.dp)
                    .height(4.dp)
                    .background(Color(0xFFBDBDBD), RoundedCornerShape(50)),
            )
        }
        return
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(4.dp)
                .background(Color(0xFFBDBDBD), RoundedCornerShape(50)),
        )
        repeat(total) { idx ->
            val active = idx == current
            Box(
                modifier = Modifier
                    .height(6.dp)
                    .width(if (active) 16.dp else 6.dp)
                    .background(
                        if (active) Color(0xFF388E3C) else Color(0xFFC8E6C9),
                        RoundedCornerShape(50),
                    ),
            )
        }
    }
}

@Composable
private fun AnnotationDetailCard(
    anomaly: AnomalyUiModel,
    onUpdate: (AnomalyUpdateRequest) -> Unit,
    onSaveReview: (AnomalyTypeUi, String, ReviewStatusUi) -> Unit,
    onTakePhoto: () -> Unit,
    onCancel: () -> Unit,
) {
    var code by remember(anomaly.id) { mutableStateOf(anomaly.buildingCode) }
    var location by remember(anomaly.id) { mutableStateOf(anomaly.location) }
    var comment by remember(anomaly.id) { mutableStateOf(anomaly.comment) }
    var type by remember(anomaly.id) { mutableStateOf(anomaly.anomalyType) }
    var reviewStatus by remember(anomaly.id) { mutableStateOf(anomaly.reviewStatus) }
    var locationDirty by remember(anomaly.id) { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val percentHint = remember(anomaly.bbox) {
        GeoAnomalyLocationResolver.fallbackPercentLabel(anomaly.bbox)
    }

    LaunchedEffect(anomaly.id) {
        code = anomaly.buildingCode
        location = anomaly.location
        locationDirty = false
        comment = anomaly.comment
        type = anomaly.anomalyType
        reviewStatus = anomaly.reviewStatus
    }

    LaunchedEffect(anomaly.location) {
        if (!locationDirty && anomaly.location.isNotBlank()) {
            location = anomaly.location
        }
    }

    LaunchedEffect(anomaly.reviewStatus) {
        reviewStatus = anomaly.reviewStatus
    }

    LaunchedEffect(code, location, comment, type, locationDirty) {
        if (GeoAnomalyLocationResolver.looksLikePercentFallback(location) && !locationDirty) {
            return@LaunchedEffect
        }
        onUpdate(
            AnomalyUpdateRequest(
                buildingCode = code,
                location = location,
                comment = comment,
                anomalyType = type,
            ),
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "建筑详情",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                AnnotationDetailActions(
                    savedStatus = anomaly.reviewStatus,
                    draftStatus = reviewStatus,
                    onCancel = onCancel,
                    onSave = { onSaveReview(type, comment, reviewStatus) },
                )
            }
            if (anomaly.photoPaths.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(Color(0xFF4CAF50), CircleShape),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "附件 ${anomaly.photoPaths.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50),
                    )
                }
            }

            // 建筑编号 / 位置
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("建筑编号") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = location,
                    onValueChange = {
                        location = it
                        locationDirty = true
                    },
                    label = { Text("位置") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    placeholder = {
                        if (location.isBlank()) Text(percentHint)
                    },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
                )
            }

            // 建筑物疑似图斑位置
            OutlinedTextField(
                value = location,
                onValueChange = {
                    location = it
                    locationDirty = true
                },
                label = { Text("建筑物疑似图斑位置") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    if (location.isBlank()) Text(percentHint)
                },
                supportingText = { Text("支持手动修改；GeoTIFF 模式下会回填经纬度") },
            )

            HorizontalDivider()

            // 形式：状态 chip 行
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("形式", style = MaterialTheme.typography.labelLarge)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    StatusChoice.entries.forEach { choice ->
                        val selected = reviewStatus == choice.status
                        FilterChip(
                            selected = selected,
                            onClick = { reviewStatus = choice.status },
                            label = { Text(choice.label) },
                            leadingIcon = if (selected) {
                                {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(Color(0xFF2E7D32), CircleShape),
                                    )
                                }
                            } else null,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFFE8F5E9),
                            ),
                        )
                    }
                }
            }

            // 人工复核：类型 chip 行
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("人工复核", style = MaterialTheme.typography.labelLarge)
                TypeChipsGrid(type = type, onTypeChange = { type = it })
            }

            // 复核时间
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("复核时间", style = MaterialTheme.typography.labelLarge)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    AssistChip(
                        onClick = onTakePhoto,
                        label = { Text("+ 增加编改附件") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = Color(0xFFE3F2FD),
                            labelColor = Color(0xFF1565C0),
                        ),
                    )
                    val ts = formatRelativeTime(anomaly.updatedAt)
                    Text(
                        text = ts,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (anomaly.photoPaths.isNotEmpty()) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(anomaly.photoPaths) { path ->
                            AsyncImage(
                                model = path,
                                contentDescription = "现场照片",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(56.dp)
                                    .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp)),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("备注") },
                    placeholder = { Text("显示校验记录") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
            }

        }
    }
}

@Composable
private fun AnnotationDetailActions(
    savedStatus: ReviewStatusUi,
    draftStatus: ReviewStatusUi,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    val compactPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    val alreadySaved = when (savedStatus) {
        ReviewStatusUi.PENDING -> false
        ReviewStatusUi.CONFIRMED -> draftStatus == ReviewStatusUi.CONFIRMED ||
            draftStatus == ReviewStatusUi.VERIFIED
        ReviewStatusUi.VERIFIED -> draftStatus == ReviewStatusUi.VERIFIED ||
            draftStatus == ReviewStatusUi.CONFIRMED
        ReviewStatusUi.REJECTED -> draftStatus == ReviewStatusUi.REJECTED
    }
    val saveLabel = when {
        alreadySaved && savedStatus == ReviewStatusUi.REJECTED -> "核验有误"
        alreadySaved -> "已保存"
        draftStatus == ReviewStatusUi.REJECTED -> "确认排除"
        else -> "保存校正"
    }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(
            onClick = onCancel,
            contentPadding = compactPadding,
        ) {
            Text("取消", style = MaterialTheme.typography.labelLarge)
        }
        Button(
            onClick = onSave,
            enabled = !alreadySaved,
            contentPadding = compactPadding,
            colors = when {
                alreadySaved && savedStatus == ReviewStatusUi.REJECTED -> ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    disabledContainerColor = MaterialTheme.colorScheme.errorContainer,
                    disabledContentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                alreadySaved -> ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF388E3C),
                    disabledContainerColor = Color(0xFF388E3C),
                    disabledContentColor = Color.White.copy(alpha = 0.8f),
                )
                else -> ButtonDefaults.buttonColors(containerColor = Color(0xFF388E3C))
            },
        ) {
            Text(saveLabel, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 状态选项：保持枚举值、显示文案与示例图一致。 */
private enum class StatusChoice(
    val status: ReviewStatusUi,
    val label: String,
) {
    ANNOTATED(ReviewStatusUi.CONFIRMED, "已标注"),
    VERIFIED(ReviewStatusUi.VERIFIED, "已核验"),
    PENDING(ReviewStatusUi.PENDING, "未标注"),
    ERROR(ReviewStatusUi.REJECTED, "核验有误"),
}

@Composable
private fun TypeChipsGrid(type: AnomalyTypeUi, onTypeChange: (AnomalyTypeUi) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            AnomalyTypeUi.entries.take(3).forEach { item ->
                FilterChip(
                    selected = type == item,
                    onClick = { onTypeChange(item) },
                    label = { Text(item.label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            AnomalyTypeUi.entries.drop(3).take(2).forEach { item ->
                FilterChip(
                    selected = type == item,
                    onClick = { onTypeChange(item) },
                    label = { Text(item.label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            AnomalyTypeUi.entries.drop(5).forEach { item ->
                FilterChip(
                    selected = type == item,
                    onClick = { onTypeChange(item) },
                    label = { Text(item.label) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun EmptyAnnotationCard(onPickAnomaly: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPickAnomaly),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("建筑详情", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "请先在影像页导入图片并完成 Building 检测，或点击下方候选建筑切换。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AnomalyChip(
    anomaly: AnomalyUiModel,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) Color(0xFF388E3C) else Color(0xFFE0E0E0)
    val container = if (isSelected) Color(0xFFE8F5E9) else MaterialTheme.colorScheme.surface
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = container,
        border = androidx.compose.foundation.BorderStroke(
            if (isSelected) 2.dp else 1.dp,
            borderColor,
        ),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (anomaly.thumbnailPath.isNotBlank()) {
                AsyncImage(
                    model = anomaly.thumbnailPath,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .border(1.dp, Color(0xFFE0E0E0), RoundedCornerShape(6.dp)),
                )
            }
            Column {
                Text(
                    text = anomaly.buildingCode.ifBlank { anomaly.id.take(8) },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) Color(0xFF1B5E20) else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = anomaly.anomalyType.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun formatRelativeTime(ts: Long): String {
    if (ts <= 0L) return "尚未复核"
    val now = System.currentTimeMillis()
    val diff = now - ts
    return when {
        diff < 60_000 -> "刚刚"
        diff < 3_600_000 -> "${diff / 60_000} 分钟前"
        diff < 86_400_000 -> "${diff / 3_600_000} 小时前"
        else -> SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
    }
}

/** 命中测试：根据点击位置查找对应的 anomaly bbox。 */
private fun hitTestAnomaly(
    offset: Offset,
    width: Float,
    height: Float,
    anomalies: List<AnomalyUiModel>,
): AnomalyUiModel? {
    val nx = offset.x / width
    val ny = offset.y / height
    return anomalies.asReversed().firstOrNull { anomaly ->
        nx >= anomaly.bbox.x &&
            nx <= anomaly.bbox.x + anomaly.bbox.width &&
            ny >= anomaly.bbox.y &&
            ny <= anomaly.bbox.y + anomaly.bbox.height
    }
}

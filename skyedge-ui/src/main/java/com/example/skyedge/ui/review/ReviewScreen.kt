package com.example.skyedge.ui.review

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.skyedge.core.api.AnomalyTypeUi
import com.example.skyedge.core.api.AnomalyUiModel
import com.example.skyedge.core.api.AnomalyUpdateRequest
import com.example.skyedge.core.api.BoundingBoxDto
import com.example.skyedge.core.api.ReviewAnomalyRequest
import com.example.skyedge.core.api.ReviewStatusUi
import com.example.skyedge.core.api.SeverityUi
import com.example.skyedge.core.api.SubmitAnomalyRequest
import com.example.skyedge.ui.inspection.InferenceViewModel
import com.example.skyedge.ui.inspection.buildMaskOverlay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    val selected = remember(uiState.anomalies, uiState.selectedAnomalyId) {
        uiState.anomalies.firstOrNull { it.id == uiState.selectedAnomalyId }
            ?: uiState.anomalies.firstOrNull()
    }
    val activeRecord = remember(uiState.recentRecords, selected?.imageLocalUrl) {
        selected?.imageLocalUrl?.let { localUrl ->
            uiState.recentRecords.firstOrNull { it.localUrl == localUrl }
        } ?: uiState.recentRecords.firstOrNull()
    }
    val overlayBitmap = remember(uiState.lastMaskPath, uiState.selectedModelKey) {
        buildMaskOverlay(uiState.lastMaskPath, uiState.selectedModelKey)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("人工核查", style = MaterialTheme.typography.headlineMedium)
        Text(activeTask?.let { "当前任务：${it.name} · ${it.status.label}" } ?: "请先在任务页创建任务")

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .border(1.dp, MaterialTheme.colorScheme.outline)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .pointerInput(uiState.anomalies) {
                    detectTapGestures { offset ->
                        hitTestAnomaly(offset, size.width.toFloat(), size.height.toFloat(), uiState.anomalies)
                            ?.let { viewModel.selectAnomaly(it.id) }
                    }
                }
                .pointerInput(activeTask?.id) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            dragStart = offset
                            dragEnd = offset
                        },
                        onDrag = { change, _ -> dragEnd = change.position },
                        onDragEnd = {
                            val start = dragStart
                            val end = dragEnd
                            val task = activeTask
                            if (start != null && end != null && task != null) {
                                val x = min(start.x, end.x) / size.width
                                val y = min(start.y, end.y) / size.height
                                val width = abs(end.x - start.x) / size.width
                                val height = abs(end.y - start.y) / size.height
                                if (width > 0.02f && height > 0.02f) {
                                    viewModel.submitAnomaly(
                                        SubmitAnomalyRequest(
                                            taskId = task.id,
                                            imageLocalUrl = uiState.recentRecords.firstOrNull()?.localUrl,
                                            bbox = BoundingBoxDto(x, y, width, height),
                                            anomalyType = AnomalyTypeUi.SUSPECTED_ILLEGAL,
                                        ),
                                    )
                                }
                            }
                            dragStart = null
                            dragEnd = null
                        },
                    )
                },
        ) {
            activeRecord?.let { record ->
                AsyncImage(
                    model = record.sourceUri,
                    contentDescription = "核查底图",
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
            overlayBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "mask 叠加",
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.FillBounds,
                )
            }
            Canvas(Modifier.fillMaxSize()) {
                uiState.anomalies.forEach { anomaly ->
                    val color = if (anomaly.id == selected?.id) Color(0xFFFFC107) else Color(0xFF4CAF50)
                    drawRect(
                        color = color,
                        topLeft = Offset(anomaly.bbox.x * size.width, anomaly.bbox.y * size.height),
                        size = Size(anomaly.bbox.width * size.width, anomaly.bbox.height * size.height),
                        style = Stroke(width = 4f),
                    )
                }
                val start = dragStart
                val end = dragEnd
                if (start != null && end != null) {
                    drawRect(
                        color = Color(0xFFFF9800),
                        topLeft = Offset(min(start.x, end.x), min(start.y, end.y)),
                        size = Size(max(1f, abs(end.x - start.x)), max(1f, abs(end.y - start.y))),
                        style = Stroke(width = 4f),
                    )
                }
            }
        }
        Text("在上方画布拖拽可新增框选异常。自动识别候选会显示为绿色框。")

        uiState.anomalies.forEach { anomaly ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.selectAnomaly(anomaly.id) },
            ) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (anomaly.thumbnailPath.isNotBlank()) {
                        AsyncImage(
                            model = anomaly.thumbnailPath,
                            contentDescription = "建筑缩略图",
                            modifier = Modifier.size(72.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${anomaly.buildingCode.ifBlank { anomaly.id.take(8) }} · ${anomaly.anomalyType.label}")
                        Text("${anomaly.reviewStatus.label} · ${anomaly.source}")
                        if (anomaly.severity.isNotBlank()) Text("严重程度：${SeverityUi.fromValue(anomaly.severity).label}")
                        if (anomaly.location.isNotBlank()) Text(anomaly.location)
                        if (anomaly.photoPaths.isNotEmpty()) Text("现场照片 ${anomaly.photoPaths.size} 张")
                    }
                }
            }
        }

        selected?.let { anomaly ->
            AnnotationCard(
                anomaly = anomaly,
                onUpdate = { viewModel.updateAnomaly(anomaly.id, it) },
                onConfirm = { type, comment ->
                    viewModel.reviewAnomaly(
                        anomaly.id,
                        ReviewAnomalyRequest(ReviewStatusUi.CONFIRMED, type, comment),
                    )
                },
                onReject = {
                    viewModel.reviewAnomaly(
                        anomaly.id,
                        ReviewAnomalyRequest(ReviewStatusUi.REJECTED, comment = "人工排除"),
                    )
                },
                onTakePhoto = { onTakePhoto(anomaly.id) },
            )
        }
    }
}

@Composable
private fun AnnotationCard(
    anomaly: AnomalyUiModel,
    onUpdate: (AnomalyUpdateRequest) -> Unit,
    onConfirm: (AnomalyTypeUi, String) -> Unit,
    onReject: () -> Unit,
    onTakePhoto: () -> Unit,
) {
    var code by remember(anomaly.id) { mutableStateOf(anomaly.buildingCode) }
    var location by remember(anomaly.id) { mutableStateOf(anomaly.location) }
    var comment by remember(anomaly.id) { mutableStateOf(anomaly.comment) }
    var type by remember(anomaly.id) { mutableStateOf(anomaly.anomalyType) }
    var severity by remember(anomaly.id) { mutableStateOf(SeverityUi.fromValue(anomaly.severity)) }

    LaunchedEffect(code, location, comment, type, severity) {
        onUpdate(
            AnomalyUpdateRequest(
                buildingCode = code,
                location = location,
                comment = comment,
                anomalyType = type,
                severity = severity.value,
            ),
        )
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("建筑详情", style = MaterialTheme.typography.titleMedium)
            if (anomaly.thumbnailPath.isNotBlank()) {
                AsyncImage(
                    model = anomaly.thumbnailPath,
                    contentDescription = "建筑缩略图",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            OutlinedTextField(value = code, onValueChange = { code = it }, label = { Text("建筑编号") })
            OutlinedTextField(value = location, onValueChange = { location = it }, label = { Text("位置") })
            OutlinedTextField(value = comment, onValueChange = { comment = it }, label = { Text("备注") })
            Text("人工标注")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf(
                    AnomalyTypeUi.NEW_BUILDING,
                    AnomalyTypeUi.SUSPECTED_ILLEGAL,
                    AnomalyTypeUi.TEMPORARY_STRUCTURE,
                ).forEach { item ->
                    OutlinedButton(onClick = { type = item }) {
                        Text(if (type == item) "✓ ${item.label}" else item.label)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = { type = AnomalyTypeUi.DAMAGED_COLLAPSED }) {
                    Text(if (type == AnomalyTypeUi.DAMAGED_COLLAPSED) "✓ 损毁/倒塌" else "损毁/倒塌")
                }
                OutlinedButton(onClick = { type = AnomalyTypeUi.OTHER }) {
                    Text(if (type == AnomalyTypeUi.OTHER) "✓ 其他" else "其他")
                }
            }
            Text("严重程度")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                SeverityUi.entries.forEach { item ->
                    OutlinedButton(onClick = { severity = item }) {
                        Text(if (severity == item) "✓ ${item.label}" else item.label)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onConfirm(type, comment) }) {
                    Text("保存标注")
                }
                OutlinedButton(onClick = onReject) {
                    Text("排除")
                }
                OutlinedButton(onClick = onTakePhoto) {
                    Text("+ 添加现场照片")
                }
            }
        }
    }
}

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

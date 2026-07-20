package com.example.skyedge.ui.report

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.skyedge.core.api.AnomalyUiModel
import com.example.skyedge.core.api.ReportFormat
import com.example.skyedge.core.api.SceneTypeUi
import com.example.skyedge.ui.inspection.InferenceViewModel
import java.io.File

private val SkyGreen = Color(0xFF23834F)
private val PageBg = Color(0xFFF5F8F2)

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen(
    viewModel: InferenceViewModel,
    onCaptureLocation: () -> Unit,
    onPickHistoricalImage: () -> Unit,
    onPickCurrentImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val task = uiState.activeTask
    val draft = uiState.reportDraft
    var reportName by remember(task?.id) { mutableStateOf(task?.name?.plus("报告") ?: "核查报告") }
    var operator by remember(task?.id) { mutableStateOf(task?.operator.orEmpty()) }
    var selectedFormats by remember(task?.id) {
        mutableStateOf(setOf(ReportFormat.PDF, ReportFormat.IMAGE, ReportFormat.JSON, ReportFormat.GEOJSON))
    }
    var pendingPreview by remember { mutableStateOf(false) }
    var showPreview by remember { mutableStateOf(false) }
    val lastReport = uiState.lastReport
    val previewImagePath = lastReport?.files?.get(ReportFormat.IMAGE)
    val draftAnomalies = draft?.anomalies.orEmpty()

    LaunchedEffect(lastReport) {
        if (pendingPreview && lastReport != null) {
            showPreview = true
            pendingPreview = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PageBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            if (task?.sceneType == SceneTypeUi.DISASTER) "灾害范围报告" else "建筑核查报告",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Black,
        )

        CardBlock {
            Text(task?.name ?: "未选择任务", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            OutlinedTextField(
                value = reportName,
                onValueChange = { reportName = it },
                label = { Text("报告名称") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = operator,
                onValueChange = { operator = it },
                label = { Text("核查人员") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("状态：${task?.status?.label ?: "-"}")
            Text("数据来源：${if (task?.sceneType == SceneTypeUi.DISASTER) "航拍底图 + GPS轨迹" else "影像识别 + 人工标注"}")
        }

        CardBlock {
            Text("核查摘要", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatText("对象", (draft?.objectCount ?: 0).toString())
                StatText("已标注", (draft?.confirmedCount ?: 0).toString())
                StatText("已排除", (draft?.rejectedCount ?: 0).toString())
            }
            draft?.typeCounts?.forEach { (type, count) ->
                Text("${type.label}: $count", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (draftAnomalies.isNotEmpty()) {
            AnomalyDetailList(draftAnomalies)
        }

        CardBlock {
            Text("附件与导出", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ReportFormat.PDF, ReportFormat.IMAGE, ReportFormat.JSON, ReportFormat.GEOJSON).forEach { format ->
                    FilterChip(
                        selected = selectedFormats.contains(format),
                        onClick = {
                            selectedFormats = if (selectedFormats.contains(format)) {
                                selectedFormats - format
                            } else {
                                selectedFormats + format
                            }
                        },
                        label = { Text(format.label) },
                    )
                }
            }
            Button(
                onClick = {
                    task?.let {
                        pendingPreview = true
                        // 预览依赖长图，生成时自动带上 IMAGE
                        val formats = selectedFormats.ifEmpty { setOf(ReportFormat.PDF) } + ReportFormat.IMAGE
                        viewModel.exportReport(it.id, formats)
                    }
                },
                enabled = task != null,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = SkyGreen),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("生成报告", fontWeight = FontWeight.Bold)
            }
            lastReport?.files?.let { files ->
                ExportedFiles(files)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (previewImagePath != null) {
                        OutlinedButton(
                            onClick = { showPreview = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("预览")
                        }
                    }
                    files.values.firstOrNull()?.let { path ->
                        OutlinedButton(
                            onClick = { shareReportFile(context, path) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                        ) {
                            Text("系统分享")
                        }
                    }
                }
            }
        }

        DisasterSection(
            pointCount = uiState.disasterTrack.points.size,
            isCollecting = uiState.disasterTrack.isCollecting,
            isClosed = uiState.disasterTrack.isClosed,
            onStart = viewModel::startDisasterTrack,
            onCaptureLocation = onCaptureLocation,
            onFinish = viewModel::finishDisasterTrack,
            onReset = viewModel::resetDisasterTrack,
        )

        CompareSection(
            historical = uiState.compareSession.historicalImageUri,
            current = uiState.compareSession.currentImageUri,
            slider = uiState.compareSession.slider,
            samStatus = uiState.compareSession.samStatus,
            onPickHistoricalImage = onPickHistoricalImage,
            onPickCurrentImage = onPickCurrentImage,
            onSlider = viewModel::setCompareSlider,
            onSamClick = { viewModel.refineMaskAt(uiState.compareSession.slider, 0.5f) },
        )
    }

    if (showPreview) {
        ReportPreviewDialog(
            imagePath = previewImagePath,
            files = lastReport?.files.orEmpty(),
            onDismiss = {
                if (showPreview) {
                    showPreview = false
                }
            },
            onShare = { path -> shareReportFile(context, path) },
        )
    }
}

@Composable
private fun ReportPreviewDialog(
    imagePath: String?,
    files: Map<ReportFormat, String>,
    onDismiss: () -> Unit,
    onShare: (String) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f),
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("报告预览", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    TextButton(onClick = onDismiss) {
                        Text("关闭", color = SkyGreen, fontWeight = FontWeight.Bold)
                    }
                }

                if (imagePath != null) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    ) {
                        AsyncImage(
                            model = imagePath,
                            contentDescription = "报告预览",
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp)),
                            contentScale = ContentScale.FillWidth,
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("未生成图片预览，可查看导出文件：", color = Color(0xFF5D6A70))
                        files.forEach { (format, path) ->
                            Text("${format.label}: $path", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val sharePath = imagePath ?: files.values.firstOrNull()
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("关闭")
                    }
                    Button(
                        onClick = { sharePath?.let(onShare) },
                        enabled = sharePath != null,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = SkyGreen),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("系统分享", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun shareReportFile(context: android.content.Context, path: String) {
    val file = File(path)
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = when (file.extension.lowercase()) {
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            "json", "geojson" -> "application/json"
            "csv" -> "text/csv"
            else -> "*/*"
        }
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享报告"))
}

@Composable
private fun CardBlock(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun StatText(label: String, value: String) {
    Column {
        Text(value, color = SkyGreen, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ExportedFiles(files: Map<ReportFormat, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("导出文件", style = MaterialTheme.typography.titleSmall)
        files.forEach { (format, path) ->
            Text("${format.label}: $path", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AnomalyDetailList(anomalies: List<AnomalyUiModel>) {
    CardBlock {
        Text("建筑明细", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        anomalies.forEach { anomaly ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                if (anomaly.thumbnailPath.isNotBlank()) {
                    AsyncImage(
                        model = anomaly.thumbnailPath,
                        contentDescription = "建筑缩略图",
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("${anomaly.buildingCode.ifBlank { anomaly.id.take(8) }} · ${anomaly.anomalyType.label}", fontWeight = FontWeight.Bold)
                    Text("${anomaly.reviewStatus.label} · ${anomaly.location.ifBlank { "暂无位置" }}", style = MaterialTheme.typography.bodySmall)
                    if (anomaly.comment.isNotBlank()) {
                        Text(anomaly.comment, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun DisasterSection(
    pointCount: Int,
    isCollecting: Boolean,
    isClosed: Boolean,
    onStart: () -> Unit,
    onCaptureLocation: () -> Unit,
    onFinish: () -> Unit,
    onReset: () -> Unit,
) {
    CardBlock {
        Text("灾害范围校正", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        Text("定位点：$pointCount 个 · 状态：${if (isClosed) "已闭合" else if (isCollecting) "采集中" else "未采集"}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart) { Text("开始采集") }
            OutlinedButton(onClick = onCaptureLocation, enabled = isCollecting) { Text("记录定位点") }
            OutlinedButton(onClick = onFinish, enabled = pointCount >= 3) { Text("保存范围") }
            OutlinedButton(onClick = onReset) { Text("重新采集") }
        }
    }
}

@Composable
private fun CompareSection(
    historical: String?,
    current: String?,
    slider: Float,
    samStatus: String,
    onPickHistoricalImage: () -> Unit,
    onPickCurrentImage: () -> Unit,
    onSlider: (Float) -> Unit,
    onSamClick: () -> Unit,
) {
    CardBlock {
        Text("双时相对比与点选修正", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onPickHistoricalImage) { Text("选择历史图") }
            OutlinedButton(onClick = onPickCurrentImage) { Text("选择本次图") }
        }
        Text("历史：${historical ?: "未选择"}", style = MaterialTheme.typography.bodySmall)
        Text("本次：${current ?: "未选择"}", style = MaterialTheme.typography.bodySmall)
        Text("卷帘位置：${(slider * 100).toInt()}%")
        Slider(value = slider, onValueChange = onSlider)
        OutlinedButton(onClick = onSamClick) {
            Text("点选修正入口")
        }
        Text(samStatus, style = MaterialTheme.typography.bodySmall)
    }
}

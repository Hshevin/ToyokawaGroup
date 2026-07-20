package com.example.skyedge.ui.report

import android.content.Intent
import androidx.core.content.FileProvider
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.skyedge.core.api.AnomalyUiModel
import com.example.skyedge.core.api.ReportFormat
import com.example.skyedge.ui.inspection.InferenceViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
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
    var reportName by remember(task?.id) { mutableStateOf(task?.name.orEmpty()) }
    var operator by remember(task?.id) { mutableStateOf(task?.operator.orEmpty()) }
    var selectedFormats by remember(task?.id) {
        mutableStateOf(
            setOf(
                ReportFormat.PDF,
                ReportFormat.IMAGE,
                ReportFormat.JSON,
                ReportFormat.GEOJSON,
            ),
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("报告", style = MaterialTheme.typography.headlineMedium)
        Card(modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(task?.name ?: "未选择任务", style = MaterialTheme.typography.titleMedium)
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
                Text("建筑对象：${draft?.objectCount ?: 0}")
                Text("已标注：${draft?.confirmedCount ?: 0} · 已排除：${draft?.rejectedCount ?: 0}")
                draft?.typeCounts?.forEach { (type, count) ->
                    Text("${type.label}: $count", style = MaterialTheme.typography.bodySmall)
                }
                Text("导出格式", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        ReportFormat.PDF,
                        ReportFormat.IMAGE,
                        ReportFormat.JSON,
                        ReportFormat.GEOJSON,
                    ).forEach { format ->
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
                            viewModel.exportReport(
                                it.id,
                                selectedFormats.ifEmpty { setOf(ReportFormat.PDF) },
                            )
                        }
                    },
                    enabled = task != null,
                ) {
                    Text("生成报告")
                }
                uiState.lastReport?.files?.let { files ->
                    ExportedFiles(files)
                }
                uiState.lastReport?.files?.get(ReportFormat.IMAGE)?.let { path ->
                    Text("长图预览", style = MaterialTheme.typography.titleSmall)
                    AsyncImage(
                        model = path,
                        contentDescription = "报告长图预览",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(360.dp),
                        contentScale = ContentScale.Fit,
                    )
                }
                uiState.lastReport?.files?.values?.firstOrNull()?.let { path ->
                    OutlinedButton(
                        onClick = {
                            val file = File(path)
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                file,
                            )
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
                        },
                    ) {
                        Text("系统分享")
                    }
                }
            }
        }

        if (!draft?.anomalies.isNullOrEmpty()) {
            AnomalyDetailList(draft?.anomalies.orEmpty())
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
    Card(modifier.fillMaxWidth()) {
        Column(modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("建筑明细", style = MaterialTheme.typography.titleMedium)
            anomalies.forEach { anomaly ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    if (anomaly.thumbnailPath.isNotBlank()) {
                        AsyncImage(
                            model = anomaly.thumbnailPath,
                            contentDescription = "建筑缩略图",
                            modifier = Modifier.size(72.dp),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("${anomaly.buildingCode.ifBlank { anomaly.id.take(8) }} · ${anomaly.anomalyType.label}")
                        Text("${anomaly.reviewStatus.label} · ${anomaly.location.ifBlank { "暂无位置" }}", style = MaterialTheme.typography.bodySmall)
                        if (anomaly.comment.isNotBlank()) {
                            Text(anomaly.comment, style = MaterialTheme.typography.bodySmall)
                        }
                        if (anomaly.photoPaths.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                anomaly.photoPaths.take(3).forEach { path ->
                                    AsyncImage(
                                        model = path,
                                        contentDescription = "现场照片",
                                        modifier = Modifier.size(52.dp),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                            }
                        }
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
    Card(modifier.fillMaxWidth()) {
        Column(modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("灾害范围校正", style = MaterialTheme.typography.titleMedium)
            Text("定位点：$pointCount 个 · 状态：${if (isClosed) "已闭合" else if (isCollecting) "采集中" else "未采集"}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onStart) { Text("开始采集") }
                OutlinedButton(onClick = onCaptureLocation, enabled = isCollecting) { Text("记录定位点") }
                OutlinedButton(onClick = onFinish, enabled = pointCount >= 3) { Text("保存范围") }
                OutlinedButton(onClick = onReset) { Text("重新采集") }
            }
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
    Card(modifier.fillMaxWidth()) {
        Column(modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("双时相对比与点选修正", style = MaterialTheme.typography.titleMedium)
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
}

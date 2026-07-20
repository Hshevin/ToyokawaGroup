package com.example.skyedge.ui.report

import android.content.Intent
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.skyedge.core.api.AnomalyUiModel
import com.example.skyedge.core.api.ReportFormat
import com.example.skyedge.ui.inspection.InferenceViewModel
import com.example.skyedge.ui.theme.SkyEdgeColors
import com.example.skyedge.ui.theme.SkyPanel
import com.example.skyedge.ui.theme.SkyPrimaryButton
import com.example.skyedge.ui.theme.SkyScreenHeader
import com.example.skyedge.ui.theme.SkySecondaryButton
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
            .verticalScroll(rememberScrollState()),
    ) {
        SkyScreenHeader(eyebrow = "报告导出", title = "建筑核查报告")
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SkyPanel {
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
                Text("状态：${task?.status?.label ?: "-"}", style = MaterialTheme.typography.bodySmall)
                Text("建筑对象：${draft?.objectCount ?: 0}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "已标注：${draft?.confirmedCount ?: 0} · 已排除：${draft?.rejectedCount ?: 0}",
                    style = MaterialTheme.typography.bodySmall,
                )
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
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SkyEdgeColors.Field,
                                selectedLabelColor = SkyEdgeColors.GreenDark,
                            ),
                        )
                    }
                }
                SkyPrimaryButton(
                    text = "生成报告",
                    onClick = {
                        task?.let {
                            viewModel.exportReport(
                                it.id,
                                selectedFormats.ifEmpty { setOf(ReportFormat.PDF) },
                            )
                        }
                    },
                    enabled = task != null,
                )
                uiState.lastReport?.files?.let { files ->
                    Text("导出文件", style = MaterialTheme.typography.titleSmall)
                    files.forEach { (format, path) ->
                        Text("${format.label}: $path", style = MaterialTheme.typography.bodySmall)
                    }
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
                    SkySecondaryButton(
                        text = "系统分享",
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
                    )
                }
            }

            val anomalies = draft?.anomalies.orEmpty()
            if (anomalies.isNotEmpty()) {
                AnomalyDetailList(anomalies)
            }

            SkyPanel {
                Text("灾害范围校正", style = MaterialTheme.typography.titleMedium)
                Text(
                    "定位点：${uiState.disasterTrack.points.size} 个 · 状态：${
                        when {
                            uiState.disasterTrack.isClosed -> "已闭合"
                            uiState.disasterTrack.isCollecting -> "采集中"
                            else -> "未采集"
                        }
                    }",
                    style = MaterialTheme.typography.bodySmall,
                )
                SkyPrimaryButton(text = "开始采集", onClick = viewModel::startDisasterTrack)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkySecondaryButton(
                        text = "记录定位点",
                        onClick = onCaptureLocation,
                        enabled = uiState.disasterTrack.isCollecting,
                        modifier = Modifier.weight(1f),
                    )
                    SkySecondaryButton(
                        text = "保存范围",
                        onClick = viewModel::finishDisasterTrack,
                        enabled = uiState.disasterTrack.points.size >= 3,
                        modifier = Modifier.weight(1f),
                    )
                }
                SkySecondaryButton(text = "重新采集", onClick = viewModel::resetDisasterTrack)
            }

            SkyPanel {
                Text("双时相对比与点选修正", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkySecondaryButton(
                        text = "选择历史图",
                        onClick = onPickHistoricalImage,
                        modifier = Modifier.weight(1f),
                    )
                    SkySecondaryButton(
                        text = "选择本次图",
                        onClick = onPickCurrentImage,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "历史：${uiState.compareSession.historicalImageUri ?: "未选择"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "本次：${uiState.compareSession.currentImageUri ?: "未选择"}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("卷帘位置：${(uiState.compareSession.slider * 100).toInt()}%")
                Slider(
                    value = uiState.compareSession.slider,
                    onValueChange = viewModel::setCompareSlider,
                )
                SkySecondaryButton(
                    text = "点选修正入口",
                    onClick = { viewModel.refineMaskAt(uiState.compareSession.slider, 0.5f) },
                )
                Text(uiState.compareSession.samStatus, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun AnomalyDetailList(anomalies: List<AnomalyUiModel>) {
    SkyPanel {
        Text("建筑明细", style = MaterialTheme.typography.titleMedium)
        anomalies.forEach { anomaly ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (anomaly.thumbnailPath.isNotBlank()) {
                    AsyncImage(
                        model = anomaly.thumbnailPath,
                        contentDescription = "建筑缩略图",
                        modifier = Modifier.size(72.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        "${anomaly.buildingCode.ifBlank { anomaly.id.take(8) }} · ${anomaly.anomalyType.label}",
                    )
                    Text(
                        "${anomaly.reviewStatus.label} · ${anomaly.location.ifBlank { "暂无位置" }}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (anomaly.comment.isNotBlank()) {
                        Text(anomaly.comment, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

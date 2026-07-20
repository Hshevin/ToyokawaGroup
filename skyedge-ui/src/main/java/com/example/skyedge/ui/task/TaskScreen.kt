package com.example.skyedge.ui.task

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.skyedge.core.api.CreateTaskRequest
import com.example.skyedge.core.api.SceneTypeUi
import com.example.skyedge.core.api.TaskUiModel
import com.example.skyedge.ui.inspection.InferenceViewModel

private val SkyGreen = Color(0xFF23834F)
private val PageBg = Color(0xFFF6F8F3)
private val CardBg = Color.White
private val Muted = Color(0xFF657277)
private val Line = Color(0xFFD8DED8)
private val HeaderBg = Color(0xFFFBFCF7)

@Composable
fun TaskScreen(
    viewModel: InferenceViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedScene by rememberSaveable { mutableStateOf<String?>(null) }
    val sceneType = when (selectedScene) {
        "building" -> SceneTypeUi.BUILDING
        "disaster" -> SceneTypeUi.DISASTER
        else -> null
    }

    if (sceneType == null) {
        ScenePickerPage(
            modifier = modifier,
            onBuilding = { selectedScene = "building" },
            onDisaster = { selectedScene = "disaster" },
        )
    } else {
        SceneTaskListPage(
            viewModel = viewModel,
            sceneType = sceneType,
            tasks = uiState.tasks.filter { it.sceneType == sceneType },
            activeTask = uiState.activeTask,
            onBack = { selectedScene = null },
            modifier = modifier,
        )
    }
}

@Composable
private fun ScenePickerPage(
    onBuilding: () -> Unit,
    onDisaster: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PageBg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HeaderBg)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("SkyEdge", color = SkyGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                Text("选择巡检场景", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            }
            OfflinePill()
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(Color(0xE617653A), Color(0xCC267F8F)),
                        ),
                    )
                    .padding(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "低空影像现场核查",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Black,
                        color = Color.White,
                    )
                    Text(
                        "选择本次任务类型，进入对应的影像导入、智能识别、人工标注与报告导出流程。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.9f),
                    )
                }
            }

            SceneEntryCard(
                icon = "▦",
                title = "城市建筑巡检",
                desc = "建筑识别、人工标注、现场留证、报告导出",
                onClick = onBuilding,
            )
            SceneEntryCard(
                icon = "⌖",
                title = "灾害巡检",
                desc = "导入航拍图、实地定位采集范围、导出灾害报告",
                onClick = onDisaster,
            )
        }
    }
}

@Composable
private fun SceneTaskListPage(
    viewModel: InferenceViewModel,
    sceneType: SceneTypeUi,
    tasks: List<TaskUiModel>,
    activeTask: TaskUiModel?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isBuilding = sceneType == SceneTypeUi.BUILDING
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PageBg),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(HeaderBg)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                border = BorderStroke(1.dp, Line),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("‹")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (isBuilding) "城市建筑巡检" else "灾害巡检",
                    color = SkyGreen,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium,
                )
                Text(
                    if (isBuilding) "建筑巡检任务" else "灾害巡检任务",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Black,
                )
            }
            OfflinePill()
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = {
                    viewModel.createTask(
                        CreateTaskRequest(
                            name = if (isBuilding) "建筑核查任务" else "灾害范围核查",
                            sceneType = sceneType,
                            areaName = if (isBuilding) "未命名核查区域" else "未命名灾害核查区域",
                            operator = "当前账号",
                        ),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = SkyGreen),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(if (isBuilding) "新建任务" else "新建灾害任务", fontWeight = FontWeight.Bold)
            }

            QuickStats(
                if (isBuilding) {
                    listOf(
                        "任务" to tasks.size.toString(),
                        "建筑对象" to tasks.sumOf { it.anomalyCount }.toString(),
                        "影像" to tasks.sumOf { it.imageCount }.toString(),
                    )
                } else {
                    listOf(
                        "任务" to tasks.size.toString(),
                        "影像" to tasks.sumOf { it.imageCount }.toString(),
                        "推进中" to tasks.count { it.status.label.contains("待") || it.status.label.contains("中") }.toString(),
                    )
                },
            )

            activeTask?.takeIf { it.sceneType == sceneType }?.let { ActiveTaskCard(it) }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (isBuilding) "建筑巡检任务" else "灾害巡检任务",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    if (isBuilding) "点击任务进入影像导入" else "点击任务进入范围校正",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }

            if (tasks.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardBg),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Line),
                ) {
                    Text(
                        "暂无任务，点击上方按钮新建。",
                        modifier = Modifier.padding(14.dp),
                        color = Muted,
                    )
                }
            } else {
                tasks.forEach { task ->
                    TaskCard(task = task, onSelect = viewModel::setActiveTask)
                }
            }
        }
    }
}

@Composable
private fun SceneEntryCard(
    icon: String,
    title: String,
    desc: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Line),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE9F4EC)),
                contentAlignment = Alignment.Center,
            ) {
                Text(icon, color = SkyGreen, style = MaterialTheme.typography.titleLarge)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                Text(desc, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
        }
    }
}

@Composable
private fun OfflinePill() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .border(1.dp, Color(0xFFCBDCCA), RoundedCornerShape(999.dp))
            .background(Color(0xFFEEF5E9))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(SkyGreen),
        )
        Text("离线可用", style = MaterialTheme.typography.bodySmall, color = Color(0xFF35533F))
    }
}

@Composable
private fun QuickStats(items: List<Pair<String, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        items.forEach { (label, value) ->
            Card(
                modifier = Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = CardBg),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Line),
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(value, color = SkyGreen, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleLarge)
                    Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
                }
            }
        }
    }
}

@Composable
private fun ActiveTaskCard(task: TaskUiModel) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFEAF3E9)),
        shape = RoundedCornerShape(10.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("当前任务", color = SkyGreen, fontWeight = FontWeight.Bold)
            Text("${task.name} · ${task.status.label}", fontWeight = FontWeight.Black)
            Text(
                "${task.sceneType.label} · ${task.areaName.ifBlank { "未命名区域" }} · ${task.operator.ifBlank { "当前账号" }}",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
        }
    }
}

@Composable
private fun TaskCard(task: TaskUiModel, onSelect: (String) -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect(task.id) },
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Line),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(task.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                StatusPill(task.status.label)
            }
            Text(
                "${task.areaName.ifBlank { "未命名核查区域" }} · ${task.operator.ifBlank { "当前账号" }}",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("影像 ${task.imageCount} 张", style = MaterialTheme.typography.bodySmall, color = Muted)
                Text(
                    "异常 ${task.anomalyCount} 条 · 优先级 ${task.priority.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
        }
    }
}

@Composable
private fun StatusPill(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFFEAF3E9))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        color = SkyGreen,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
    )
}

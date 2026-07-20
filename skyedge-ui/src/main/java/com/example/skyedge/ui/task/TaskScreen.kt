package com.example.skyedge.ui.task

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.skyedge.core.api.CreateTaskRequest
import com.example.skyedge.core.api.SceneTypeUi
import com.example.skyedge.core.api.TaskStatusUi
import com.example.skyedge.ui.inspection.InferenceViewModel
import com.example.skyedge.ui.theme.SkyHeroBanner
import com.example.skyedge.ui.theme.SkyPanel
import com.example.skyedge.ui.theme.SkyPrimaryButton
import com.example.skyedge.ui.theme.SkyScreenHeader
import com.example.skyedge.ui.theme.SkySecondaryButton
import com.example.skyedge.ui.theme.SkyStatCellWeighted
import com.example.skyedge.ui.theme.StatusPill

@Composable
fun TaskScreen(
    viewModel: InferenceViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val buildingTasks = uiState.tasks.filter { it.sceneType == SceneTypeUi.BUILDING }
    val disasterTasks = uiState.tasks.filter { it.sceneType == SceneTypeUi.DISASTER }
    val anomalyTotal = uiState.tasks.sumOf { it.anomalyCount }
    val advancing = uiState.tasks.count {
        it.status == TaskStatusUi.READY_TO_EXPORT || it.status == TaskStatusUi.MANUAL_REVIEW
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        SkyScreenHeader(eyebrow = "任务管理", title = "城市建筑巡检")
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SkyHeroBanner(
                title = "低空影像现场核查",
                subtitle = "选择任务类型，进入影像导入、智能识别、人工标注与报告导出流程。",
            )

            SkyPrimaryButton(
                text = "+  新建建筑核查",
                onClick = {
                    viewModel.createTask(
                        CreateTaskRequest(name = "建筑核查任务", sceneType = SceneTypeUi.BUILDING),
                    )
                },
            )
            SkySecondaryButton(
                text = "新建灾害任务",
                onClick = {
                    viewModel.createTask(
                        CreateTaskRequest(name = "灾害范围核查", sceneType = SceneTypeUi.DISASTER),
                    )
                },
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SkyStatCellWeighted(value = "${uiState.tasks.size}", label = "任务")
                SkyStatCellWeighted(value = "$anomalyTotal", label = "建筑对象")
                SkyStatCellWeighted(value = "$advancing", label = "推进中")
            }

            Text(
                text = uiState.activeTask?.let { "当前任务：${it.name} · ${it.status.label}" }
                    ?: "请先创建或选择任务",
                style = MaterialTheme.typography.bodySmall,
            )

            SectionHead(title = "建筑巡检任务", hint = "点击任务进入影像导入")
            if (buildingTasks.isEmpty()) {
                Text("暂无建筑任务", style = MaterialTheme.typography.bodySmall)
            } else {
                buildingTasks.forEach { task ->
                    TaskCard(
                        name = task.name,
                        status = task.status.label,
                        ready = task.status == TaskStatusUi.READY_TO_EXPORT ||
                            task.status == TaskStatusUi.MANUAL_REVIEW,
                        meta = "${task.sceneType.label} · 优先级 ${task.priority.label}",
                        foot = "影像 ${task.imageCount} 张 · 异常 ${task.anomalyCount} 条",
                        area = listOf(task.areaName, task.operator)
                            .filter { it.isNotBlank() }
                            .joinToString(" "),
                        selected = uiState.activeTask?.id == task.id,
                        onClick = { viewModel.setActiveTask(task.id) },
                    )
                }
            }

            if (disasterTasks.isNotEmpty()) {
                SectionHead(title = "灾害巡检任务", hint = "点击任务进入范围校正")
                disasterTasks.forEach { task ->
                    TaskCard(
                        name = task.name,
                        status = task.status.label,
                        ready = task.status == TaskStatusUi.READY_TO_EXPORT,
                        meta = "${task.sceneType.label} · 优先级 ${task.priority.label}",
                        foot = "影像 ${task.imageCount} 张 · 异常 ${task.anomalyCount} 条",
                        area = listOf(task.areaName, task.operator)
                            .filter { it.isNotBlank() }
                            .joinToString(" "),
                        selected = uiState.activeTask?.id == task.id,
                        onClick = { viewModel.setActiveTask(task.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHead(title: String, hint: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(hint, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun TaskCard(
    name: String,
    status: String,
    ready: Boolean,
    meta: String,
    foot: String,
    area: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    SkyPanel(modifier = Modifier.clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            StatusPill(text = status, ready = ready)
        }
        Text(meta, style = MaterialTheme.typography.bodySmall)
        Text(foot, style = MaterialTheme.typography.bodySmall)
        if (area.isNotBlank()) {
            Text(area, style = MaterialTheme.typography.bodySmall)
        }
        if (selected) {
            Text("已选为当前任务", style = MaterialTheme.typography.labelMedium)
        }
    }
}

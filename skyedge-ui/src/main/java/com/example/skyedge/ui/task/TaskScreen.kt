package com.example.skyedge.ui.task

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.skyedge.core.api.CreateTaskRequest
import com.example.skyedge.core.api.SceneTypeUi
import com.example.skyedge.ui.inspection.InferenceViewModel

@Composable
fun TaskScreen(
    viewModel: InferenceViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("巡检任务", style = MaterialTheme.typography.headlineMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    viewModel.createTask(
                        CreateTaskRequest(name = "建筑核查任务", sceneType = SceneTypeUi.BUILDING),
                    )
                },
            ) {
                Text("新建建筑核查")
            }
            OutlinedButton(
                onClick = {
                    viewModel.createTask(
                        CreateTaskRequest(name = "灾害范围核查", sceneType = SceneTypeUi.DISASTER),
                    )
                },
            ) {
                Text("新建灾害任务")
            }
        }

        Text(
            text = uiState.activeTask?.let { "当前任务：${it.name} · ${it.status.label}" } ?: "请先创建或选择任务",
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(4.dp))
        uiState.tasks.forEach { task ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { viewModel.setActiveTask(task.id) },
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium)
                    Text("${task.sceneType.label} · ${task.status.label} · 优先级 ${task.priority.label}")
                    Text("影像 ${task.imageCount} 张 · 异常 ${task.anomalyCount} 条")
                    if (task.areaName.isNotBlank() || task.operator.isNotBlank()) {
                        Text("${task.areaName} ${task.operator}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

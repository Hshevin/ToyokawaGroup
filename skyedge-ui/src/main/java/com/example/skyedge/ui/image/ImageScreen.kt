package com.example.skyedge.ui.image

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.skyedge.ui.inspection.InferenceViewModel
import com.example.skyedge.ui.inspection.InspectionScreen
import com.example.skyedge.ui.map.MapScreen
import com.example.skyedge.ui.theme.SkyEdgeColors
import com.example.skyedge.ui.theme.SkyPanel
import com.example.skyedge.ui.theme.SkyPrimaryButton
import com.example.skyedge.ui.theme.SkyScreenHeader
import com.example.skyedge.ui.theme.SkySecondaryButton

@Composable
fun ImageScreen(
    viewModel: InferenceViewModel,
    selectedImageUri: Uri?,
    onPickImage: () -> Unit,
    onLoadSample: (String) -> Unit,
    isAmapKeyConfigured: Boolean,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    if (uiState.mapSession != null) {
        MapScreen(
            viewModel = viewModel,
            isAmapKeyConfigured = isAmapKeyConfigured,
            modifier = modifier,
        )
    } else {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            SkyScreenHeader(eyebrow = "城市建筑巡检", title = "影像识别")
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SkyPanel {
                    Text(
                        uiState.activeTask?.name ?: "未选择任务",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        "导入影像后端侧会自动识别文件格式：GeoTIFF 按坐标覆盖到高德卫星底图，普通图片直接进入建筑识别。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                Text("模型", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    viewModel.modelChoices.forEach { choice ->
                        val selected = uiState.selectedModelKey == choice.key
                        Button(
                            onClick = { viewModel.switchModel(choice.key) },
                            enabled = !uiState.isInferring && !uiState.isLoadingModel,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) SkyEdgeColors.Green else Color.White,
                                contentColor = if (selected) Color.White else SkyEdgeColors.Ink,
                            ),
                            border = BorderStroke(1.dp, SkyEdgeColors.Line),
                        ) {
                            Text(
                                if (selected) "已选 ${choice.label}" else choice.label,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SkySecondaryButton(
                        text = "↑  导入影像",
                        onClick = onPickImage,
                        enabled = uiState.isModelReady && !uiState.isInferring,
                        modifier = Modifier.weight(1f),
                    )
                    SkyPrimaryButton(
                        text = "内置样例图",
                        onClick = { onLoadSample("sample_images/building_mvp_demo.json") },
                        enabled = uiState.isModelReady && !uiState.isInferring,
                        modifier = Modifier.weight(1f),
                    )
                }

                SkyPanel {
                    Text("识别结果", style = MaterialTheme.typography.titleMedium)
                    if (uiState.isInferring || uiState.isLoadingModel) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(modifier = Modifier.padding(4.dp))
                            Text(uiState.statusMessage, style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        Text(uiState.statusMessage, style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (selectedImageUri != null) {
                    InspectionScreen(
                        viewModel = viewModel,
                        selectedImageUri = selectedImageUri,
                        onPickImage = onPickImage,
                        embedded = true,
                    )
                }
            }
        }
    }
}

package com.example.skyedge.ui.image

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.skyedge.ui.inspection.InferenceViewModel
import com.example.skyedge.ui.inspection.InspectionScreen
import com.example.skyedge.ui.map.MapScreen

private val SkyGreen = Color(0xFF23834F)
private val PageBg = Color(0xFFF5F8F2)

@Composable
fun ImageScreen(
    viewModel: InferenceViewModel,
    selectedImageUri: Uri?,
    onImportImage: () -> Unit,
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
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(PageBg)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("影像识别", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text(
            "导入影像后自动识别文件格式：GeoTIFF 按坐标进入高德地图覆盖，普通图片直接进入建筑识别。",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF5D6A70),
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("模型", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewModel.modelChoices.forEach { choice ->
                        Button(
                            onClick = { viewModel.switchModel(choice.key) },
                            enabled = !uiState.isInferring && !uiState.isLoadingModel,
                        ) {
                            Text(if (uiState.selectedModelKey == choice.key) "已选 ${choice.label}" else choice.label)
                        }
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            shape = RoundedCornerShape(10.dp),
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = onImportImage,
                        enabled = uiState.isModelReady && !uiState.isInferring,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("导入影像")
                    }
                    OutlinedButton(
                        onClick = { onLoadSample("sample_images/building_mvp_demo.json") },
                        enabled = uiState.isModelReady && !uiState.isInferring,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("内置样例图")
                    }
                }
                if (!isAmapKeyConfigured) {
                    Text(
                        "未配置 AMAP_API_KEY 时，GeoTIFF 地图底图不可用；普通图片检测不受影响。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                LoadingStatus(
                    loading = uiState.isInferring || uiState.isLoadingModel || uiState.mapSession?.isLoadingGeo == true,
                    message = uiState.statusMessage,
                )
            }
        }

        if (selectedImageUri == null) {
            EmptyImageStage()
            ResultPanel(
                title = "识别结果",
                detail = "导入普通航拍图后会直接调用端侧模型生成建筑 mask；导入 GeoTIFF 会进入地图覆盖后再检测。",
                status = "待导入",
            )
        } else {
            InspectionScreen(
                viewModel = viewModel,
                selectedImageUri = selectedImageUri,
                onPickImage = onImportImage,
                embedded = true,
            )
        }
    }
}

@Composable
private fun LoadingStatus(loading: Boolean, message: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (loading) {
            CircularProgressIndicator()
        }
        Text(message, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun EmptyImageStage() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(420.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFDDE8D8)),
        contentAlignment = Alignment.Center,
    ) {
        Text("等待导入影像", color = Color(0xFF5D6A70), fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ResultPanel(title: String, detail: String, status: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF5D6A70))
            }
            Text(
                status,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFFEAF3E9))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                color = SkyGreen,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

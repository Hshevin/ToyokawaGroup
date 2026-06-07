package com.example.skyedge.ui.inspection

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage

@Composable
fun InspectionScreen(
    viewModel: InferenceViewModel,
    selectedImageUri: Uri?,
    onPickImage: () -> Unit,
    onBenchmark: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "低空巡检 AI 原型",
            style = MaterialTheme.typography.headlineMedium,
        )
        Spacer(modifier = Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            viewModel.modelChoices.forEach { choice ->
                Button(
                    onClick = { viewModel.switchModel(choice.key) },
                    enabled = !uiState.isInferring && !uiState.isLoadingModel,
                ) {
                    val isSelected = uiState.selectedModelKey == choice.key
                    Text(if (isSelected) "已选 ${choice.label}" else "切换 ${choice.label}")
                }
            }
        }
        Spacer(modifier = Modifier.height(20.dp))

        val maskPath = uiState.lastMaskPath
        val overlayBitmap = remember(maskPath) {
            buildMaskOverlay(maskPath)
        }
        if (selectedImageUri != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.Top,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("原图", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    AsyncImage(
                        model = selectedImageUri,
                        contentDescription = "Original Image",
                        modifier = Modifier.size(170.dp),
                        contentScale = ContentScale.Crop,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("处理后对比", style = MaterialTheme.typography.labelMedium)
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(modifier = Modifier.size(170.dp)) {
                        AsyncImage(
                            model = selectedImageUri,
                            contentDescription = "Processed Base Image",
                            modifier = Modifier.matchParentSize(),
                            contentScale = ContentScale.Crop,
                        )
                        if (overlayBitmap != null) {
                            Image(
                                bitmap = overlayBitmap.asImageBitmap(),
                                contentDescription = "Mask Overlay",
                                modifier = Modifier.matchParentSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        if (uiState.isLoadingModel || uiState.isInferring) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
        }

        Button(
            onClick = onPickImage,
            enabled = uiState.isModelReady && !uiState.isInferring,
        ) {
            Text(
                when {
                    uiState.isLoadingModel -> "模型加载中"
                    !uiState.isModelReady -> "模型未就绪"
                    uiState.isInferring -> "推理中"
                    else -> "导入现场照片"
                },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = onBenchmark,
            enabled = uiState.isModelReady && !uiState.isInferring && selectedImageUri != null,
        ) {
            Text("当前图连跑10次")
        }

        Spacer(modifier = Modifier.height(20.dp))
        Text(text = uiState.statusMessage)

        if (uiState.recentRecords.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "本地检测记录（Room）",
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 180.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                uiState.recentRecords.forEach { item ->
                    Text(
                        text = buildString {
                            append("[${item.status}] ${item.analyseType}")
                            if (item.detail.isNotBlank()) append(" · ${item.detail}")
                            append("\n")
                            append(item.localUrl)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

package com.example.skyedge.ui.image

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.skyedge.ui.inspection.InferenceViewModel
import com.example.skyedge.ui.inspection.InspectionScreen
import com.example.skyedge.ui.map.MapScreen

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
        val scrollState = rememberScrollState()
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("影像导入", style = MaterialTheme.typography.headlineMedium)
            Text(
                "按文件格式自动分流：.tif/.tiff/.geotiff 覆盖到地图；普通照片仅本地识别、不上图。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onPickImage,
                    enabled = uiState.isModelReady && !uiState.isInferring,
                ) {
                    Text("导入影像")
                }
                OutlinedButton(
                    onClick = { onLoadSample("sample_images/building_mvp_demo.json") },
                    enabled = uiState.isModelReady && !uiState.isInferring,
                ) {
                    Text("内置样例图")
                }
            }
            if (uiState.isInferring || uiState.isLoadingModel || uiState.mapSession?.isLoadingGeo == true) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator()
                    Text(uiState.statusMessage)
                }
            } else {
                Text(uiState.statusMessage)
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

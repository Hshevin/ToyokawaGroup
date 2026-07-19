package com.example.skyedge.ui.map

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.skyedge.core.api.AnomalyTypeUi
import com.example.skyedge.core.api.AnomalyUiModel
import com.example.skyedge.core.api.ReviewAnomalyRequest
import com.example.skyedge.core.api.ReviewStatusUi
import com.example.skyedge.ui.inspection.InferenceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    viewModel: InferenceViewModel,
    isAmapKeyConfigured: Boolean,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mapViewModel = remember(viewModel) { MapViewModel(viewModel) }
    val mapSession = uiState.mapSession
    val selectedAnomaly = remember(uiState.anomalies, uiState.selectedAnomalyId) {
        uiState.anomalies.firstOrNull { it.id == uiState.selectedAnomalyId }
    }
    val openImport = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(viewModel::importImage) },
    )

    Box(modifier = modifier.fillMaxSize()) {
        AMapCompose(
            mapSession = mapSession,
            onMapClick = { lat, lng ->
                val hit = MapAnomalyHitTest.hitTest(lat, lng, mapSession, uiState.anomalies)
                viewModel.selectAnomaly(hit?.id)
            },
            modifier = Modifier.fillMaxSize(),
        )

        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp),
            tonalElevation = 6.dp,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "SkyEdge 地图巡检",
                    style = MaterialTheme.typography.titleMedium,
                )
                if (!isAmapKeyConfigured) {
                    Text(
                        text = "请先在 local.properties 配置 AMAP_API_KEY",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewModel.modelChoices.forEach { choice ->
                        Button(
                            onClick = { mapViewModel.switchModel(choice.key) },
                            enabled = uiState.isModelReady && !uiState.isInferring && !uiState.isLoadingModel,
                        ) {
                            val selected = uiState.selectedModelKey == choice.key
                            Text(if (selected) "已选 ${choice.label}" else choice.label)
                        }
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            openImport.launch(arrayOf("image/*", "image/tiff", "image/x-tiff", "*/*"))
                        },
                        enabled = isAmapKeyConfigured && !uiState.isInferring,
                    ) {
                        Text("导入影像")
                    }
                    Button(
                        onClick = mapViewModel::inferMapSession,
                        enabled = isAmapKeyConfigured &&
                            uiState.isModelReady &&
                            !uiState.isInferring &&
                            mapSession != null,
                    ) {
                        Text(if (uiState.isInferring) "检测中" else "开始检测")
                    }
                    OutlinedButton(
                        onClick = mapViewModel::clearMapSession,
                        enabled = mapSession != null && !uiState.isInferring,
                    ) {
                        Text("清除")
                    }
                }
                if (uiState.isInferring || mapSession?.isLoadingGeo == true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator()
                        Text(uiState.statusMessage, style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(uiState.statusMessage, style = MaterialTheme.typography.bodySmall)
                }

                mapSession?.let { session ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LayerSwitch(
                            label = "正射",
                            checked = session.showOrtho,
                            onCheckedChange = {
                                mapViewModel.setLayerVisibility(it, session.showMask)
                            },
                        )
                        LayerSwitch(
                            label = "Mask",
                            checked = session.showMask,
                            onCheckedChange = {
                                mapViewModel.setLayerVisibility(session.showOrtho, it)
                            },
                        )
                    }
                    Text(
                        text = "Mask 透明度：${(session.maskAlpha * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = session.maskAlpha,
                        onValueChange = mapViewModel::setMaskAlpha,
                        valueRange = 0.1f..0.8f,
                        enabled = session.maskOverlayPath != null,
                    )
                    session.geoError?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } ?: Spacer(modifier = Modifier.height(1.dp))
            }
        }

        selectedAnomaly?.let { anomaly ->
            ModalBottomSheet(
                onDismissRequest = { viewModel.selectAnomaly(null) },
            ) {
                MapAnomalySheet(
                    anomaly = anomaly,
                    onConfirm = { type ->
                        viewModel.reviewAnomaly(
                            anomaly.id,
                            ReviewAnomalyRequest(ReviewStatusUi.CONFIRMED, type, anomaly.comment),
                        )
                    },
                    onReject = {
                        viewModel.reviewAnomaly(
                            anomaly.id,
                            ReviewAnomalyRequest(ReviewStatusUi.REJECTED, comment = "地图点击排除"),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun MapAnomalySheet(
    anomaly: AnomalyUiModel,
    onConfirm: (AnomalyTypeUi) -> Unit,
    onReject: () -> Unit,
) {
    var type by remember(anomaly.id) { mutableStateOf(anomaly.anomalyType) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (anomaly.thumbnailPath.isNotBlank()) {
                AsyncImage(
                    model = anomaly.thumbnailPath,
                    contentDescription = "建筑缩略图",
                    modifier = Modifier.size(88.dp),
                    contentScale = ContentScale.Crop,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(anomaly.buildingCode.ifBlank { anomaly.id.take(8) }, style = MaterialTheme.typography.titleMedium)
                Text("来源：${anomaly.source}")
                Text("状态：${anomaly.reviewStatus.label}")
                if (anomaly.location.isNotBlank()) Text(anomaly.location)
            }
        }
        Text("人工标注")
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                AnomalyTypeUi.NEW_BUILDING,
                AnomalyTypeUi.SUSPECTED_ILLEGAL,
                AnomalyTypeUi.TEMPORARY_STRUCTURE,
            ).forEach { item ->
                OutlinedButton(onClick = { type = item }) {
                    Text(if (type == item) "✓ ${item.label}" else item.label)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(AnomalyTypeUi.DAMAGED_COLLAPSED, AnomalyTypeUi.OTHER).forEach { item ->
                OutlinedButton(onClick = { type = item }) {
                    Text(if (type == item) "✓ ${item.label}" else item.label)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onConfirm(type) }) {
                Text("保存标注")
            }
            OutlinedButton(onClick = onReject) {
                Text("排除")
            }
        }
    }
}

@Composable
private fun LayerSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

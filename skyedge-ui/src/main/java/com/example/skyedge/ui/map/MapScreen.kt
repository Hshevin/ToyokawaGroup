package com.example.skyedge.ui.map

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.skyedge.core.api.AnomalyTypeUi
import com.example.skyedge.core.api.AnomalyUiModel
import com.example.skyedge.core.api.ReviewAnomalyRequest
import com.example.skyedge.core.api.ReviewStatusUi
import com.example.skyedge.ui.inspection.InferenceViewModel
import com.example.skyedge.ui.theme.SkyEdgeColors
import com.example.skyedge.ui.theme.SkyPrimaryButton
import com.example.skyedge.ui.theme.SkySecondaryButton

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
            shape = RoundedCornerShape(8.dp),
            color = SkyEdgeColors.Surface,
            border = BorderStroke(1.dp, SkyEdgeColors.Line),
            shadowElevation = 0.dp,
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "地图巡检",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyEdgeColors.Ink,
                )
                Text(
                    text = "正射叠加 · Mask 调透明度 · 点选建筑核查",
                    style = MaterialTheme.typography.bodySmall,
                    color = SkyEdgeColors.Muted,
                )
                if (!isAmapKeyConfigured) {
                    Text(
                        text = "请先在 local.properties 配置 AMAP_API_KEY",
                        color = SkyEdgeColors.Red,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    viewModel.modelChoices.forEach { choice ->
                        val selected = uiState.selectedModelKey == choice.key
                        FilterChip(
                            selected = selected,
                            onClick = { mapViewModel.switchModel(choice.key) },
                            enabled = uiState.isModelReady && !uiState.isInferring && !uiState.isLoadingModel,
                            label = { Text(if (selected) "已选 ${choice.label}" else choice.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SkyEdgeColors.Field,
                                selectedLabelColor = SkyEdgeColors.GreenDark,
                                containerColor = SkyEdgeColors.Surface,
                                labelColor = SkyEdgeColors.Muted,
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = SkyEdgeColors.Line,
                                selectedBorderColor = SkyEdgeColors.SoftGreenBorder,
                            ),
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Button(
                        onClick = {
                            openImport.launch(arrayOf("image/*", "image/tiff", "image/x-tiff", "*/*"))
                        },
                        enabled = isAmapKeyConfigured && !uiState.isInferring,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SkyEdgeColors.Green,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text("导入影像")
                    }
                    Button(
                        onClick = mapViewModel::inferMapSession,
                        enabled = isAmapKeyConfigured &&
                            uiState.isModelReady &&
                            !uiState.isInferring &&
                            mapSession != null,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SkyEdgeColors.GreenDark,
                            contentColor = Color.White,
                        ),
                    ) {
                        Text(if (uiState.isInferring) "检测中" else "开始检测")
                    }
                    OutlinedButton(
                        onClick = mapViewModel::clearMapSession,
                        enabled = mapSession != null && !uiState.isInferring,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.White,
                            contentColor = SkyEdgeColors.Ink,
                        ),
                        border = BorderStroke(1.dp, SkyEdgeColors.Line),
                    ) {
                        Text("清除")
                    }
                }
                if (uiState.isInferring || mapSession?.isLoadingGeo == true) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = SkyEdgeColors.Green,
                            strokeWidth = 2.dp,
                        )
                        Text(
                            uiState.statusMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = SkyEdgeColors.Muted,
                        )
                    }
                } else {
                    Text(
                        uiState.statusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = SkyEdgeColors.Muted,
                    )
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
                        color = SkyEdgeColors.Muted,
                    )
                    Slider(
                        value = session.maskAlpha,
                        onValueChange = mapViewModel::setMaskAlpha,
                        valueRange = 0.1f..0.8f,
                        enabled = session.maskOverlayPath != null,
                        colors = SliderDefaults.colors(
                            thumbColor = SkyEdgeColors.Green,
                            activeTrackColor = SkyEdgeColors.Green,
                            inactiveTrackColor = SkyEdgeColors.Line,
                        ),
                    )
                    session.geoError?.let {
                        Text(
                            text = it,
                            color = SkyEdgeColors.Red,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                } ?: Spacer(modifier = Modifier.height(1.dp))
            }
        }

        selectedAnomaly?.let { anomaly ->
            ModalBottomSheet(
                onDismissRequest = { viewModel.selectAnomaly(null) },
                containerColor = SkyEdgeColors.Surface,
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
        Text(
            text = "建筑对象",
            style = MaterialTheme.typography.labelMedium,
            color = SkyEdgeColors.Muted,
        )
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
                Text(
                    anomaly.buildingCode.ifBlank { anomaly.id.take(8) },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = SkyEdgeColors.Ink,
                )
                Text("来源：${anomaly.source.ifBlank { "建筑识别自动圈定" }}", color = SkyEdgeColors.Muted)
                Text("状态：${anomaly.reviewStatus.label}", color = SkyEdgeColors.Muted)
                if (anomaly.location.isNotBlank()) {
                    Text(anomaly.location, color = SkyEdgeColors.Ink)
                }
            }
        }
        Text("人工标注", style = MaterialTheme.typography.labelLarge, color = SkyEdgeColors.Ink)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(
                AnomalyTypeUi.NEW_BUILDING,
                AnomalyTypeUi.SUSPECTED_ILLEGAL,
                AnomalyTypeUi.TEMPORARY_STRUCTURE,
            ).forEach { item ->
                val selected = type == item
                FilterChip(
                    selected = selected,
                    onClick = { type = item },
                    label = { Text(item.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SkyEdgeColors.Field,
                        selectedLabelColor = SkyEdgeColors.GreenDark,
                    ),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            listOf(AnomalyTypeUi.DAMAGED_COLLAPSED, AnomalyTypeUi.OTHER).forEach { item ->
                val selected = type == item
                FilterChip(
                    selected = selected,
                    onClick = { type = item },
                    label = { Text(item.label) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SkyEdgeColors.Field,
                        selectedLabelColor = SkyEdgeColors.GreenDark,
                    ),
                )
            }
        }
        SkyPrimaryButton(text = "保存标注", onClick = { onConfirm(type) })
        SkySecondaryButton(text = "排除", onClick = onReject)
        Spacer(modifier = Modifier.height(12.dp))
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
        Text(label, style = MaterialTheme.typography.bodySmall, color = SkyEdgeColors.Ink)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = SkyEdgeColors.Green,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = SkyEdgeColors.Line,
            ),
        )
    }
}

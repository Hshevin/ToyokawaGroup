package com.example.skyedge

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.Image
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage

class MainActivity : ComponentActivity() {

    private val viewModel: InferenceViewModel by viewModels()
    private var selectedImageUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val uiState = viewModel.uiState

            val galleryLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent(),
                onResult = { uri: Uri? ->
                    selectedImageUri = uri
                    uri?.let { viewModel.infer(it) }
                },
            )

            val permissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission(),
                onResult = { granted ->
                    if (granted) {
                        galleryLauncher.launch("image/*")
                    } else {
                        viewModel.updateStatus("需要相册权限才能选择图片")
                    }
                },
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
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
                    onClick = {
                        if (needsReadImagesPermission() &&
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.READ_MEDIA_IMAGES,
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            permissionLauncher.launch(Manifest.permission.READ_MEDIA_IMAGES)
                        } else {
                            galleryLauncher.launch("image/*")
                        }
                    },
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
                    onClick = {
                        selectedImageUri?.let { viewModel.benchmarkCurrentImage(it, runs = 10) }
                    },
                    enabled = uiState.isModelReady && !uiState.isInferring && selectedImageUri != null,
                ) {
                    Text("当前图连跑10次")
                }

                Spacer(modifier = Modifier.height(20.dp))
                Text(text = uiState.statusMessage)
            }
        }
    }

    private fun needsReadImagesPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun buildMaskOverlay(maskPath: String?): Bitmap? {
        if (maskPath.isNullOrBlank()) return null
        val mask = BitmapFactory.decodeFile(maskPath) ?: return null
        val width = mask.width
        val height = mask.height
        val src = IntArray(width * height)
        val dst = IntArray(width * height)
        mask.getPixels(src, 0, width, 0, 0, width, height)
        for (i in src.indices) {
            val gray = src[i] and 0xFF
            dst[i] = if (gray > 0) {
                Color.argb((0.42f * 255).toInt(), 255, 0, 0)
            } else {
                Color.TRANSPARENT
            }
        }
        val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        overlay.setPixels(dst, 0, width, 0, 0, width, height)
        mask.recycle()
        return overlay
    }
}

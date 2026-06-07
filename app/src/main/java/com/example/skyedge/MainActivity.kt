package com.example.skyedge

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.example.skyedge.ui.inspection.InferenceViewModel
import com.example.skyedge.ui.inspection.InspectionScreen

class MainActivity : ComponentActivity() {

    private val viewModel: InferenceViewModel by viewModels()
    private var selectedImageUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
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

            InspectionScreen(
                viewModel = viewModel,
                selectedImageUri = selectedImageUri,
                onPickImage = {
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
                onBenchmark = {
                    selectedImageUri?.let { viewModel.benchmarkCurrentImage(it, runs = 10) }
                },
            )
        }
    }

    private fun needsReadImagesPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
}

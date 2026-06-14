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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.example.skyedge.ui.inspection.InferenceViewModel
import com.example.skyedge.ui.inspection.InspectionScreen
import com.example.skyedge.ui.map.MapScreen

class MainActivity : ComponentActivity() {

    private val viewModel: InferenceViewModel by viewModels()
    private var selectedImageUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasAmapApiKey()) {
            viewModel.updateStatus("请在 local.properties 配置 AMAP_API_KEY 后使用地图")
        }

        setContent {
            var selectedTab by rememberSaveable { mutableStateOf(AppTab.MAP) }
            val isAmapKeyConfigured = remember { hasAmapApiKey() }
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

            Scaffold(
                bottomBar = {
                    NavigationBar {
                        AppTab.entries.forEach { tab ->
                            NavigationBarItem(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab },
                                label = { Text(tab.label) },
                                icon = {},
                            )
                        }
                    }
                },
            ) { innerPadding ->
                when (selectedTab) {
                    AppTab.MAP -> MapScreen(
                        viewModel = viewModel,
                        isAmapKeyConfigured = isAmapKeyConfigured,
                        modifier = Modifier.padding(innerPadding),
                    )
                    AppTab.INSPECTION -> InspectionScreen(
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
                    )
                }
            }
        }
    }

    private fun needsReadImagesPermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private fun hasAmapApiKey(): Boolean {
        val appInfo = packageManager.getApplicationInfo(
            packageName,
            PackageManager.GET_META_DATA,
        )
        return appInfo.metaData?.getString("com.amap.api.v2.apikey").orEmpty().isNotBlank()
    }

    private enum class AppTab(val label: String) {
        MAP("地图"),
        INSPECTION("检测"),
    }
}

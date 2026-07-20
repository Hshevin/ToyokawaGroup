package com.example.skyedge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.skyedge.core.geo.GeoTiffDetector
import com.example.skyedge.ui.image.ImageScreen
import com.example.skyedge.ui.inspection.InferenceViewModel
import com.example.skyedge.ui.report.ReportScreen
import com.example.skyedge.ui.review.ReviewScreen
import com.example.skyedge.ui.task.TaskScreen
import com.example.skyedge.ui.theme.SkyEdgeColors
import com.example.skyedge.ui.theme.SkyEdgeTheme
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * 真功能入口：底栏 Tab + InferenceViewModel。
 * UI 视觉来自 skyedge-ui-handoff-20260720（对齐 Web 原型），保留导入分流与业务 launcher。
 */
class MainActivity : ComponentActivity() {

    private val viewModel: InferenceViewModel by viewModels()
    private var selectedImageUri by mutableStateOf<Uri?>(null)
    private var pendingPhotoAnomalyId: String? = null
    private var pendingCameraUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!hasAmapApiKey()) {
            viewModel.updateStatus("请在 local.properties 配置 AMAP_API_KEY 后使用地图")
        }

        setContent {
            SkyEdgeTheme {
                var selectedTab by rememberSaveable { mutableStateOf(AppTab.TASK) }
                val imageImportLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.OpenDocument(),
                    onResult = { uri: Uri? ->
                        uri ?: return@rememberLauncherForActivityResult
                        try {
                            contentResolver.takePersistableUriPermission(
                                uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION,
                            )
                        } catch (_: SecurityException) {
                        }
                        if (GeoTiffDetector.isGeoTiff(this@MainActivity, uri)) {
                            selectedImageUri = null
                            viewModel.importImage(uri)
                        } else {
                            selectedImageUri = uri
                            viewModel.importImage(uri)
                        }
                    },
                )
                val historicalImageLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent(),
                    onResult = { uri: Uri? ->
                        viewModel.setCompareImages(uri, null)
                    },
                )
                val currentCompareImageLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent(),
                    onResult = { uri: Uri? ->
                        viewModel.setCompareImages(null, uri)
                    },
                )
                val takePictureLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.TakePicture(),
                    onResult = { success ->
                        val anomalyId = pendingPhotoAnomalyId
                        val uri = pendingCameraUri
                        if (success && anomalyId != null && uri != null) {
                            viewModel.attachPhoto(anomalyId, uri)
                        }
                        pendingPhotoAnomalyId = null
                        pendingCameraUri = null
                    },
                )
                val cameraPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { granted ->
                        if (granted) {
                            pendingCameraUri?.let { takePictureLauncher.launch(it) }
                        } else {
                            viewModel.updateStatus("需要相机权限才能拍照取证")
                        }
                    },
                )
                val locationPermissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { granted ->
                        if (granted) {
                            viewModel.captureCurrentLocation()
                        } else {
                            viewModel.updateStatus("需要定位权限才能记录 GPS 点")
                        }
                    },
                )

                Scaffold(
                    containerColor = SkyEdgeColors.Paper,
                    bottomBar = {
                        NavigationBar(
                            containerColor = SkyEdgeColors.Header,
                            contentColor = SkyEdgeColors.Ink,
                        ) {
                            AppTab.entries.forEach { tab ->
                                NavigationBarItem(
                                    selected = selectedTab == tab,
                                    onClick = { selectedTab = tab },
                                    label = { Text(tab.label) },
                                    icon = {},
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = SkyEdgeColors.Green,
                                        selectedTextColor = SkyEdgeColors.Green,
                                        indicatorColor = SkyEdgeColors.Field,
                                        unselectedIconColor = SkyEdgeColors.Muted,
                                        unselectedTextColor = SkyEdgeColors.Muted,
                                    ),
                                )
                            }
                        }
                    },
                ) { innerPadding ->
                    when (selectedTab) {
                        AppTab.TASK -> TaskScreen(
                            viewModel = viewModel,
                            modifier = Modifier.padding(innerPadding),
                        )
                        AppTab.IMAGE -> ImageScreen(
                            viewModel = viewModel,
                            selectedImageUri = selectedImageUri,
                            onImportImage = {
                                imageImportLauncher.launch(IMAGE_IMPORT_MIME_TYPES)
                            },
                            onLoadSample = { assetPath ->
                                val uri = renderSampleImage(assetPath)
                                selectedImageUri = uri
                                viewModel.clearMapSession()
                                viewModel.infer(uri)
                            },
                            isAmapKeyConfigured = hasAmapApiKey(),
                            modifier = Modifier.padding(innerPadding),
                        )
                        AppTab.REVIEW -> ReviewScreen(
                            viewModel = viewModel,
                            onTakePhoto = { anomalyId ->
                                val uri = createCameraUri()
                                pendingPhotoAnomalyId = anomalyId
                                pendingCameraUri = uri
                                if (ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.CAMERA,
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    takePictureLauncher.launch(uri)
                                } else {
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                }
                            },
                            modifier = Modifier.padding(innerPadding),
                        )
                        AppTab.REPORT -> ReportScreen(
                            viewModel = viewModel,
                            onCaptureLocation = {
                                if (ContextCompat.checkSelfPermission(
                                        this@MainActivity,
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                    ) == PackageManager.PERMISSION_GRANTED
                                ) {
                                    viewModel.captureCurrentLocation()
                                } else {
                                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            },
                            onPickHistoricalImage = { historicalImageLauncher.launch("image/*") },
                            onPickCurrentImage = { currentCompareImageLauncher.launch("image/*") },
                            modifier = Modifier.padding(innerPadding),
                        )
                    }
                }
            }
        }
    }

    private fun hasAmapApiKey(): Boolean {
        val appInfo = packageManager.getApplicationInfo(
            packageName,
            PackageManager.GET_META_DATA,
        )
        return appInfo.metaData?.getString("com.amap.api.v2.apikey").orEmpty().isNotBlank()
    }

    private fun createCameraUri(): Uri {
        val dir = File(cacheDir, "camera").apply { mkdirs() }
        val file = File(dir, "evidence_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private fun renderSampleImage(assetPath: String): Uri {
        val spec = assets.open(assetPath).bufferedReader().use { JSONObject(it.readText()) }
        val width = spec.optInt("width", 768)
        val height = spec.optInt("height", 512)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawColor(Color.parseColor(spec.optString("background", "#D7E6D0")))
        drawRects(canvas, paint, spec.optJSONArray("roads"))
        drawRects(canvas, paint, spec.optJSONArray("buildings"))

        val dir = File(cacheDir, "samples").apply { mkdirs() }
        val file = File(dir, "building_mvp_demo.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        viewModel.updateStatus("已加载内置样例图：${spec.optString("name", "sample")}")
        return Uri.fromFile(file)
    }

    private fun drawRects(canvas: Canvas, paint: Paint, array: org.json.JSONArray?) {
        if (array == null) return
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            paint.color = Color.parseColor(item.optString("color", "#CCCCCC"))
            val left = item.optDouble("x").toFloat()
            val top = item.optDouble("y").toFloat()
            canvas.drawRect(
                left,
                top,
                left + item.optDouble("width").toFloat(),
                top + item.optDouble("height").toFloat(),
                paint,
            )
        }
    }

    private enum class AppTab(val label: String) {
        TASK("任务"),
        IMAGE("影像"),
        REVIEW("核查"),
        REPORT("报告"),
    }

    private companion object {
        val IMAGE_IMPORT_MIME_TYPES = arrayOf(
            "image/*",
            "image/tiff",
            "image/x-tiff",
            "application/octet-stream",
            "*/*",
        )
    }
}

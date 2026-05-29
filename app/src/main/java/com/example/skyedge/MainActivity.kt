package com.example.skyedge // 确保包名和你的一致

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import org.pytorch.torchvision.TensorImageUtils
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

class MainActivity : ComponentActivity() {

    // 1. 定义变量
    private var module: Module? = null
    private var selectedImageUri by mutableStateOf<Uri?>(null)
    private var resultText by mutableStateOf("请点击按钮选择图片")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 2. 初始化模型 (放在这里是为了只加载一次)
        try {
            module = Module.load(assetFilePath(this, "model.pt")) // 这里的名字要和assets里的文件名一致！
            resultText = "模型加载成功！请选择图片"
        } catch (e: Exception) {
            resultText = "模型加载失败: ${e.message}"
            e.printStackTrace()
        }

        setContent {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "低空巡检 AI 原型", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(20.dp))

                // 3. 显示选中的图片
                selectedImageUri?.let { uri ->
                    AsyncImage(
                        model = uri,
                        contentDescription = "Selected Image",
                        modifier = Modifier.size(300.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 4. 选择图片按钮
                val launcher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.GetContent(),
                    onResult = { uri: Uri? ->
                        selectedImageUri = uri
                        // 选中图片后自动触发推理（简化流程）
                        uri?.let { runInference(it) }
                    }
                )

                Button(onClick = {
                    // 启动相册选择器
                    launcher.launch("image/*")
                }) {
                    Text("导入现场照片")
                }

                Spacer(modifier = Modifier.height(20.dp))

                // 5. 显示结果
                Text(text = resultText)
            }
        }
    }

    // 6. 核心推理函数
    private fun runInference(uri: Uri) {
        if (module == null) {
            resultText = "模型未加载，无法推理"
            return
        }

        try {
            // A. 将 Uri 转为 Bitmap
            val bitmap = BitmapFactory.decodeStream(contentResolver.openInputStream(uri))

            // B. 预处理：调整大小并转为 Tensor
            // 注意：这里的 224 是常见模型的输入尺寸，你需要问算法同学他们的模型输入是多少（比如 256, 512?）
            val inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                bitmap,
                TensorImageUtils.TORCHVISION_NORM_MEAN_RGB,
                TensorImageUtils.TORCHVISION_NORM_STD_RGB
            )

            // C. 执行推理
            val outputTensor = module!!.forward(IValue.from(inputTensor)).toTensor()

            // D. 解析结果 (这里假设是分类任务，如果是分割任务需要更复杂的处理)
            val scores = outputTensor.dataAsFloatArray
            val maxScoreIdx = scores.indices.maxByOrNull { scores[it] } ?: -1

            resultText = "推理完成！\n最大概率类别索引: $maxScoreIdx\n置信度: ${scores[maxScoreIdx]}"

        } catch (e: Exception) {
            resultText = "推理出错: ${e.message}"
            e.printStackTrace()
        }
    }

    // 辅助函数：从 Assets 复制文件到内部存储（PyTorch Android 需要文件路径）
    private fun assetFilePath(context: ComponentActivity, assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (file.exists() && file.length() > 0) {
            return file.absolutePath
        }
        context.assets.open(assetName).use { inputStream ->
            FileOutputStream(file).use { outputStream ->
                val buffer = ByteArray(4 * 1024)
                var read: Int
                while (inputStream.read(buffer).also { read = it } != -1) {
                    outputStream.write(buffer, 0, read)
                }
                outputStream.flush()
            }
            return file.absolutePath
        }
    }
}
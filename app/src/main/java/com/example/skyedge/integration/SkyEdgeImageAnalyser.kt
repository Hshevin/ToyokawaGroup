package com.example.skyedge.integration

import android.content.Context
import android.net.Uri
import com.example.skyedge.model.ImagePreprocessor
import com.example.skyedge.model.InferenceEngine
import com.example.skyedge.model.ModelChoice
import com.example.skyedge.domain.InspectionResult
import imgrecord.ImageAnalyser
import imgrecord.model.AnalyseResult
import imgrecord.model.AnalyseStatus
import imgrecord.model.AnalyseType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * 对接 [Hshevin/ToyokawaGroup-DatabaseComponent](https://github.com/Hshevin/ToyokawaGroup-DatabaseComponent)
 * 的 [ImageAnalyser] 接口：由 ImageRecordRepository 在 insert 后异步回调。
 */
class SkyEdgeImageAnalyser(
    private val context: Context,
    private val engine: InferenceEngine,
) : ImageAnalyser {

    override suspend fun analyse(
        localUrl: String,
        imgUrl: String,
        analyseType: AnalyseType,
    ): AnalyseResult = withContext(Dispatchers.Default) {
        val specAsset = specAssetFor(analyseType)
        engine.load(specAsset).getOrElse { error ->
            return@withContext AnalyseResult(
                status = AnalyseStatus.FAILED,
                time = System.currentTimeMillis(),
                summaryJson = "",
                errInfo = error.message ?: error.toString(),
            )
        }

        val uri = Uri.parse(imgUrl)
        val bitmap = ImagePreprocessor.loadOrientedBitmap(context, uri)
            ?: return@withContext AnalyseResult(
                status = AnalyseStatus.FAILED,
                time = System.currentTimeMillis(),
                summaryJson = "",
                errInfo = "无法读取图片: $imgUrl",
            )

        val outputDir = File(localUrl.trimEnd(File.separatorChar))
        val inferOutcome = runCatching {
            engine.infer(bitmap, outputDir).getOrThrow()
        }
        bitmap.recycle()
        val finishedAt = System.currentTimeMillis()
        inferOutcome.fold(
            onSuccess = { result ->
                when (result) {
                    is InspectionResult.Segmentation -> AnalyseResult(
                        status = AnalyseStatus.DONE,
                        time = finishedAt,
                        summaryJson = enrichSummary(
                            baseJson = result.summaryJson,
                            maskPath = result.maskPath,
                            inferenceMs = result.inferenceMs,
                            modelVersion = result.modelVersion,
                            localUrl = localUrl,
                            analyseType = analyseType,
                        ),
                    )
                    is InspectionResult.Classification -> AnalyseResult(
                        status = AnalyseStatus.DONE,
                        time = finishedAt,
                        summaryJson = enrichSummary(
                            baseJson = """{"task":"classification","class_index":${result.classIndex},"confidence":${result.confidence}}""",
                            maskPath = null,
                            inferenceMs = result.inferenceMs,
                            modelVersion = result.modelVersion,
                            localUrl = localUrl,
                            analyseType = analyseType,
                        ),
                    )
                    is InspectionResult.Error -> AnalyseResult(
                        status = AnalyseStatus.FAILED,
                        time = finishedAt,
                        summaryJson = "",
                        errInfo = result.message,
                    )
                }
            },
            onFailure = { error ->
                AnalyseResult(
                    status = AnalyseStatus.FAILED,
                    time = finishedAt,
                    summaryJson = "",
                    errInfo = error.message ?: error.toString(),
                )
            },
        )
    }

    private fun specAssetFor(analyseType: AnalyseType): String = when (analyseType) {
        AnalyseType.BUILDING -> ModelChoice.BUILDING.specAsset
        AnalyseType.ROAD -> ModelChoice.ROAD.specAsset
    }

    companion object {
        fun enrichSummary(
            baseJson: String,
            maskPath: String?,
            inferenceMs: Long,
            modelVersion: String,
            localUrl: String,
            analyseType: AnalyseType,
        ): String {
            val root = runCatching { JSONObject(baseJson) }.getOrElse { JSONObject() }
            root.put("local_url", localUrl)
            root.put("analyse_type", analyseType.name.lowercase())
            root.put("inference_ms", inferenceMs)
            root.put("model_version", modelVersion)
            maskPath?.let { root.put("mask_path", it) }
            return root.toString()
        }

        fun maskPathFromSummary(summaryJson: String): String? =
            runCatching { JSONObject(summaryJson).optString("mask_path") }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
    }
}

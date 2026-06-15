package com.example.skyedge.core.model

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.Uri
import imgrecord.model.AnomalyRecord
import imgrecord.model.ImageRecord
import imgrecord.model.ReviewStatus
import imgrecord.model.TaskRecord
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

object ReportImageComposer {
    private const val PAGE_WIDTH = 1080
    private const val PAGE_PADDING = 48f
    private const val CONTENT_WIDTH = PAGE_WIDTH - PAGE_PADDING * 2

    fun compose(
        context: Context,
        outputFile: File,
        task: TaskRecord,
        anomalies: List<AnomalyRecord>,
        records: List<ImageRecord>,
    ): String {
        val primaryRecord = records.firstOrNull()
        val source = primaryRecord?.let { ImagePreprocessor.loadOrientedBitmap(context, Uri.parse(it.imgUrl)) }
        val previewHeight = source?.let {
            (CONTENT_WIDTH * it.height / it.width).toInt().coerceIn(320, 760)
        } ?: 0
        val detailRows = anomalies.take(18)
        val pageHeight = (240 + previewHeight + 80 + detailRows.size * 118 + 160).coerceAtLeast(1500)
        val bitmap = android.graphics.Bitmap.createBitmap(PAGE_WIDTH, pageHeight, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        canvas.drawColor(Color.WHITE)
        var y = drawHeader(canvas, paint, task, anomalies)
        if (source != null) {
            primaryRecord?.let { record ->
                y = drawPreview(canvas, paint, source, record, anomalies, y)
            }
            source.recycle()
        } else {
            paint.color = Color.DKGRAY
            paint.textSize = 28f
            canvas.drawText("暂无可预览原图", PAGE_PADDING, y + 60f, paint)
            y += 140f
        }
        drawDetails(canvas, paint, detailRows, y + 42f)

        outputFile.parentFile?.mkdirs()
        FileOutputStream(outputFile).use { out ->
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()
        return outputFile.absolutePath
    }

    private fun drawHeader(
        canvas: Canvas,
        paint: Paint,
        task: TaskRecord,
        anomalies: List<AnomalyRecord>,
    ): Float {
        paint.color = Color.rgb(27, 94, 32)
        paint.textSize = 50f
        paint.isFakeBoldText = true
        canvas.drawText(task.name, PAGE_PADDING, 88f, paint)
        paint.isFakeBoldText = false
        paint.color = Color.DKGRAY
        paint.textSize = 28f
        val confirmed = anomalies.count { it.reviewStatus == ReviewStatus.CONFIRMED }
        val rejected = anomalies.count { it.reviewStatus == ReviewStatus.REJECTED }
        canvas.drawText("建筑对象 ${anomalies.size} · 已标注 $confirmed · 已排除 $rejected", PAGE_PADDING, 142f, paint)
        canvas.drawText("生成时间：${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format(java.util.Date())}", PAGE_PADDING, 184f, paint)
        return 220f
    }

    private fun drawPreview(
        canvas: Canvas,
        paint: Paint,
        source: android.graphics.Bitmap,
        record: ImageRecord,
        anomalies: List<AnomalyRecord>,
        top: Float,
    ): Float {
        val imageHeight = (CONTENT_WIDTH * source.height / source.width).coerceIn(320f, 760f)
        val rect = RectF(PAGE_PADDING, top, PAGE_PADDING + CONTENT_WIDTH, top + imageHeight)
        canvas.drawBitmap(source, null, rect, paint)
        maskPathFromSummary(record.summaryJson)?.let { maskPath ->
            runCatching { MaskOverlayRenderer.renderMaskBitmap(maskPath, record.analyseType.name.lowercase(), 0.42f) }
                .getOrNull()
                ?.let { overlay ->
                    canvas.drawBitmap(overlay, null, rect, paint)
                    overlay.recycle()
                }
        }
        anomalies.forEach { anomaly ->
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            paint.color = when (anomaly.reviewStatus) {
                ReviewStatus.CONFIRMED -> Color.rgb(46, 125, 50)
                ReviewStatus.REJECTED -> Color.rgb(117, 117, 117)
                ReviewStatus.PENDING -> Color.rgb(255, 193, 7)
            }
            canvas.drawRect(
                rect.left + anomaly.bbox.x * rect.width(),
                rect.top + anomaly.bbox.y * rect.height(),
                rect.left + (anomaly.bbox.x + anomaly.bbox.width) * rect.width(),
                rect.top + (anomaly.bbox.y + anomaly.bbox.height) * rect.height(),
                paint,
            )
            paint.style = Paint.Style.FILL
            paint.textSize = 24f
            canvas.drawText(
                anomaly.buildingCode.ifBlank { anomaly.id.take(6) },
                rect.left + anomaly.bbox.x * rect.width(),
                (rect.top + anomaly.bbox.y * rect.height() - 8f).coerceAtLeast(rect.top + 28f),
                paint,
            )
        }
        paint.style = Paint.Style.FILL
        return rect.bottom
    }

    private fun drawDetails(
        canvas: Canvas,
        paint: Paint,
        anomalies: List<AnomalyRecord>,
        top: Float,
    ) {
        paint.color = Color.rgb(27, 94, 32)
        paint.textSize = 34f
        paint.isFakeBoldText = true
        canvas.drawText("建筑明细", PAGE_PADDING, top, paint)
        paint.isFakeBoldText = false
        var y = top + 40f
        anomalies.forEach { anomaly ->
            val rowTop = y
            paint.color = Color.rgb(245, 245, 245)
            canvas.drawRoundRect(
                RectF(PAGE_PADDING, rowTop, PAGE_PADDING + CONTENT_WIDTH, rowTop + 96f),
                16f,
                16f,
                paint,
            )
            anomaly.thumbnailPath.takeIf { it.isNotBlank() }?.let { path ->
                BitmapFactory.decodeFile(path)?.let { thumbnail ->
                    canvas.drawBitmap(thumbnail, null, RectF(PAGE_PADDING + 14f, rowTop + 12f, PAGE_PADDING + 86f, rowTop + 84f), paint)
                    thumbnail.recycle()
                }
            }
            paint.color = Color.DKGRAY
            paint.textSize = 26f
            canvas.drawText(
                "${anomaly.buildingCode.ifBlank { anomaly.id.take(8) }}  ${typeLabel(anomaly.anomalyType.value)}  ${statusLabel(anomaly.reviewStatus)}",
                PAGE_PADDING + 108f,
                rowTop + 38f,
                paint,
            )
            paint.textSize = 22f
            val note = listOf(anomaly.location, anomaly.comment).filter { it.isNotBlank() }.joinToString(" · ")
            canvas.drawText(note.ifBlank { "暂无位置/备注" }, PAGE_PADDING + 108f, rowTop + 72f, paint)
            y += 112f
        }
    }

    private fun maskPathFromSummary(summaryJson: String): String? =
        runCatching { JSONObject(summaryJson).optString("mask_path") }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    private fun typeLabel(value: String): String = when (value) {
        "new_building" -> "新建建筑"
        "suspected_illegal" -> "疑似违建"
        "temporary_structure" -> "临时搭建"
        "damaged_collapsed" -> "损毁/倒塌"
        "debris" -> "堆积物"
        "landslide" -> "滑坡"
        else -> "其他"
    }

    private fun statusLabel(status: ReviewStatus): String = when (status) {
        ReviewStatus.PENDING -> "待核查"
        ReviewStatus.CONFIRMED -> "已标注"
        ReviewStatus.REJECTED -> "已排除"
    }
}

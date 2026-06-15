package imgrecord

import android.content.Context
import imgrecord.db.AnomalyDao
import imgrecord.db.AnomalyEntity
import imgrecord.db.ImageRecordDatabase
import imgrecord.model.AnomalyRecord
import imgrecord.model.AnomalyType
import imgrecord.model.BoundingBoxRecord
import imgrecord.model.ReviewStatus
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

class AnomalyRepository(
    private val dao: AnomalyDao,
) {
    constructor(context: Context) : this(ImageRecordDatabase.create(context).anomalyDao())

    suspend fun insertDraft(
        taskId: String,
        imageLocalUrl: String?,
        bbox: BoundingBoxRecord,
        buildingCode: String,
        source: String = SOURCE_AUTO_SEGMENTATION,
        thumbnailPath: String = "",
    ): AnomalyRecord {
        val now = System.currentTimeMillis()
        val record = AnomalyRecord(
            id = UUID.randomUUID().toString(),
            taskId = taskId,
            imageLocalUrl = imageLocalUrl,
            bbox = bbox,
            anomalyType = AnomalyType.OTHER,
            reviewStatus = ReviewStatus.PENDING,
            buildingCode = buildingCode,
            location = "",
            source = source,
            comment = "",
            severity = "",
            thumbnailPath = thumbnailPath,
            photoPaths = emptyList(),
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(record.toEntity())
        return record
    }

    suspend fun upsert(record: AnomalyRecord) {
        dao.upsert(record.copy(updatedAt = System.currentTimeMillis()).toEntity())
    }

    suspend fun get(id: String): AnomalyRecord? =
        dao.getById(id)?.toModel()

    suspend fun listByTask(taskId: String): List<AnomalyRecord> =
        dao.getByTaskId(taskId).map { it.toModel() }

    suspend fun listByImage(imageLocalUrl: String): List<AnomalyRecord> =
        dao.getByImageLocalUrl(imageLocalUrl).map { it.toModel() }

    suspend fun countByTask(taskId: String): Int =
        dao.countByTaskId(taskId)

    suspend fun updateReview(
        id: String,
        status: ReviewStatus,
        anomalyType: AnomalyType? = null,
        comment: String? = null,
    ): AnomalyRecord? {
        val current = get(id) ?: return null
        val updated = current.copy(
            reviewStatus = status,
            anomalyType = anomalyType ?: current.anomalyType,
            comment = comment ?: current.comment,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(updated.toEntity())
        return updated
    }

    suspend fun updateFields(
        id: String,
        anomalyType: AnomalyType? = null,
        reviewStatus: ReviewStatus? = null,
        buildingCode: String? = null,
        location: String? = null,
        comment: String? = null,
        severity: String? = null,
        thumbnailPath: String? = null,
        bbox: BoundingBoxRecord? = null,
    ): AnomalyRecord? {
        val current = get(id) ?: return null
        val updated = current.copy(
            anomalyType = anomalyType ?: current.anomalyType,
            reviewStatus = reviewStatus ?: current.reviewStatus,
            buildingCode = buildingCode ?: current.buildingCode,
            location = location ?: current.location,
            comment = comment ?: current.comment,
            severity = severity ?: current.severity,
            thumbnailPath = thumbnailPath ?: current.thumbnailPath,
            bbox = bbox ?: current.bbox,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(updated.toEntity())
        return updated
    }

    suspend fun attachPhoto(id: String, path: String): AnomalyRecord? {
        val current = get(id) ?: return null
        val updated = current.copy(
            photoPaths = current.photoPaths + path,
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsert(updated.toEntity())
        return updated
    }

    companion object {
        const val SOURCE_AUTO_SEGMENTATION = "auto_segmentation"
        const val SOURCE_MANUAL_BOX = "manual_box"
    }
}

private fun AnomalyRecord.toEntity(): AnomalyEntity =
    AnomalyEntity(
        id = id,
        taskId = taskId,
        imageLocalUrl = imageLocalUrl,
        bboxJson = bbox.toJson().toString(),
        anomalyType = anomalyType.value,
        reviewStatus = reviewStatus.value,
        buildingCode = buildingCode,
        location = location,
        source = source,
        comment = comment,
        severity = severity,
        thumbnailPath = thumbnailPath,
        photoPathsJson = JSONArray(photoPaths).toString(),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun AnomalyEntity.toModel(): AnomalyRecord =
    AnomalyRecord(
        id = id,
        taskId = taskId,
        imageLocalUrl = imageLocalUrl,
        bbox = bboxJson.toBoundingBox(),
        anomalyType = AnomalyType.fromValue(anomalyType),
        reviewStatus = ReviewStatus.fromValue(reviewStatus),
        buildingCode = buildingCode,
        location = location,
        source = source,
        comment = comment,
        severity = severity,
        thumbnailPath = thumbnailPath,
        photoPaths = photoPathsJson.toStringList(),
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun BoundingBoxRecord.toJson(): JSONObject =
    JSONObject()
        .put("x", x.toDouble())
        .put("y", y.toDouble())
        .put("width", width.toDouble())
        .put("height", height.toDouble())

private fun String.toBoundingBox(): BoundingBoxRecord {
    val json = runCatching { JSONObject(this) }.getOrElse { JSONObject() }
    return BoundingBoxRecord(
        x = json.optDouble("x", 0.0).toFloat(),
        y = json.optDouble("y", 0.0).toFloat(),
        width = json.optDouble("width", 0.0).toFloat(),
        height = json.optDouble("height", 0.0).toFloat(),
    )
}

private fun String.toStringList(): List<String> {
    val array = runCatching { JSONArray(this) }.getOrElse { JSONArray() }
    return buildList {
        for (i in 0 until array.length()) {
            array.optString(i).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

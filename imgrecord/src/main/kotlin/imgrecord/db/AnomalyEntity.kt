package imgrecord.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "anomaly",
    indices = [
        Index(value = ["task_id"]),
        Index(value = ["image_local_url"]),
    ],
)
data class AnomalyEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "task_id")
    val taskId: String,
    @ColumnInfo(name = "image_local_url")
    val imageLocalUrl: String?,
    @ColumnInfo(name = "bbox_json")
    val bboxJson: String,
    @ColumnInfo(name = "anomaly_type")
    val anomalyType: String,
    @ColumnInfo(name = "review_status")
    val reviewStatus: String,
    @ColumnInfo(name = "building_code")
    val buildingCode: String,
    @ColumnInfo(name = "location")
    val location: String,
    @ColumnInfo(name = "source")
    val source: String,
    @ColumnInfo(name = "comment")
    val comment: String,
    @ColumnInfo(name = "severity")
    val severity: String,
    @ColumnInfo(name = "thumbnail_path")
    val thumbnailPath: String,
    @ColumnInfo(name = "photo_paths_json")
    val photoPathsJson: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

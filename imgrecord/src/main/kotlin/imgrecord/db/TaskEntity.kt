package imgrecord.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "task")
data class TaskEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "scene_type")
    val sceneType: String,
    @ColumnInfo(name = "area_name")
    val areaName: String,
    @ColumnInfo(name = "status")
    val status: String,
    @ColumnInfo(name = "priority")
    val priority: String,
    @ColumnInfo(name = "operator")
    val operator: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

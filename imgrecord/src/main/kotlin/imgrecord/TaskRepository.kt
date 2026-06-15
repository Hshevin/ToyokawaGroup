package imgrecord

import android.content.Context
import imgrecord.db.ImageRecordDatabase
import imgrecord.db.TaskDao
import imgrecord.db.TaskEntity
import imgrecord.model.SceneType
import imgrecord.model.TaskPriority
import imgrecord.model.TaskRecord
import imgrecord.model.TaskStatus
import java.util.UUID

class TaskRepository(
    private val dao: TaskDao,
) {
    constructor(context: Context) : this(ImageRecordDatabase.create(context).taskDao())

    suspend fun create(
        name: String,
        sceneType: SceneType = SceneType.BUILDING,
        areaName: String = "",
        priority: TaskPriority = TaskPriority.NORMAL,
        operator: String = "",
    ): TaskRecord {
        val now = System.currentTimeMillis()
        val record = TaskRecord(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { defaultName(sceneType) },
            sceneType = sceneType,
            areaName = areaName,
            status = TaskStatus.DRAFT,
            priority = priority,
            operator = operator,
            createdAt = now,
            updatedAt = now,
        )
        dao.upsert(record.toEntity())
        return record
    }

    suspend fun upsert(record: TaskRecord) {
        dao.upsert(record.toEntity())
    }

    suspend fun updateStatus(id: String, status: TaskStatus): TaskRecord? {
        val current = dao.getById(id)?.toModel() ?: return null
        val updated = current.copy(status = status, updatedAt = System.currentTimeMillis())
        dao.upsert(updated.toEntity())
        return updated
    }

    suspend fun get(id: String): TaskRecord? =
        dao.getById(id)?.toModel()

    suspend fun list(): List<TaskRecord> =
        dao.getAll().map { it.toModel() }

    suspend fun delete(id: String): Boolean =
        dao.deleteById(id) > 0

    private fun defaultName(sceneType: SceneType): String = when (sceneType) {
        SceneType.BUILDING -> "建筑核查任务"
        SceneType.DISASTER -> "灾害范围核查"
    }
}

private fun TaskRecord.toEntity(): TaskEntity =
    TaskEntity(
        id = id,
        name = name,
        sceneType = sceneType.value,
        areaName = areaName,
        status = status.value,
        priority = priority.value,
        operator = operator,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

private fun TaskEntity.toModel(): TaskRecord =
    TaskRecord(
        id = id,
        name = name,
        sceneType = SceneType.fromValue(sceneType),
        areaName = areaName,
        status = TaskStatus.fromValue(status),
        priority = TaskPriority.fromValue(priority),
        operator = operator,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

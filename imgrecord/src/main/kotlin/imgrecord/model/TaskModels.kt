package imgrecord.model

enum class SceneType(val value: String) {
    BUILDING("building"),
    DISASTER("disaster");

    companion object {
        fun fromValue(value: String?): SceneType =
            entries.find { it.value == value } ?: BUILDING
    }
}

enum class TaskStatus(val value: String) {
    DRAFT("draft"),
    MANUAL_REVIEW("manual_review"),
    READY_TO_EXPORT("ready_to_export"),
    EXPORTED("exported"),
    PENDING_REPORT("pending_report");

    companion object {
        fun fromValue(value: String?): TaskStatus =
            entries.find { it.value == value } ?: DRAFT
    }
}

enum class TaskPriority(val value: String) {
    LOW("low"),
    NORMAL("normal"),
    HIGH("high");

    companion object {
        fun fromValue(value: String?): TaskPriority =
            entries.find { it.value == value } ?: NORMAL
    }
}

data class TaskRecord(
    val id: String,
    val name: String,
    val sceneType: SceneType,
    val areaName: String,
    val status: TaskStatus,
    val priority: TaskPriority,
    val operator: String,
    val createdAt: Long,
    val updatedAt: Long,
)

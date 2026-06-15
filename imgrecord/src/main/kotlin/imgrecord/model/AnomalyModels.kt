package imgrecord.model

enum class AnomalyType(val value: String) {
    NEW_BUILDING("new_building"),
    SUSPECTED_ILLEGAL("suspected_illegal"),
    TEMPORARY_STRUCTURE("temporary_structure"),
    DAMAGED_COLLAPSED("damaged_collapsed"),
    DEBRIS("debris"),
    LANDSLIDE("landslide"),
    OTHER("other");

    companion object {
        fun fromValue(value: String?): AnomalyType =
            entries.find { it.value == value } ?: OTHER
    }
}

enum class ReviewStatus(val value: String) {
    PENDING("pending"),
    CONFIRMED("confirmed"),
    REJECTED("rejected");

    companion object {
        fun fromValue(value: String?): ReviewStatus =
            entries.find { it.value == value } ?: PENDING
    }
}

data class BoundingBoxRecord(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)

data class AnomalyRecord(
    val id: String,
    val taskId: String,
    val imageLocalUrl: String?,
    val bbox: BoundingBoxRecord,
    val anomalyType: AnomalyType,
    val reviewStatus: ReviewStatus,
    val buildingCode: String,
    val location: String,
    val source: String,
    val comment: String,
    val severity: String,
    val thumbnailPath: String,
    val photoPaths: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
)

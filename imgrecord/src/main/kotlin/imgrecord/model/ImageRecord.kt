package imgrecord.model

data class ImageRecord(
    val localUrl: String,
    val imgUrl: String,
    val analyseType: AnalyseType,
    val status: AnalyseStatus,
    val time: Long,
    val summaryJson: String,
    val errInfo: String?,
    val taskId: String? = null,
)

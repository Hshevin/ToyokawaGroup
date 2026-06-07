package imgrecord.model

data class AnalyseResult(
    val status: AnalyseStatus,
    val time: Long,
    val summaryJson: String,
    val errInfo: String? = null,
)

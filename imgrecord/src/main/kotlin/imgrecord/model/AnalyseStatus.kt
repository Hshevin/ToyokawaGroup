package imgrecord.model

enum class AnalyseStatus(val value: Int) {
    PENDING(0),
    DONE(1),
    FAILED(2);

    companion object {
        fun fromInt(value: Int): AnalyseStatus =
            entries.find { it.value == value } ?: PENDING
    }
}

package imgrecord.model

enum class AnalyseType(val value: Int) {
    BUILDING(0),
    ROAD(1);

    companion object {
        fun fromInt(value: Int): AnalyseType =
            entries.find { it.value == value } ?: BUILDING
    }
}

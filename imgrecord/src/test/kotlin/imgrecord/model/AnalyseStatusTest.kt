package imgrecord.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyseStatusTest {

    @Test
    fun fromInt_returnsMatchingStatus() {
        assertEquals(AnalyseStatus.PENDING, AnalyseStatus.fromInt(0))
        assertEquals(AnalyseStatus.DONE, AnalyseStatus.fromInt(1))
        assertEquals(AnalyseStatus.FAILED, AnalyseStatus.fromInt(2))
    }

    @Test
    fun fromInt_unknownValue_defaultsToPending() {
        assertEquals(AnalyseStatus.PENDING, AnalyseStatus.fromInt(99))
        assertEquals(AnalyseStatus.PENDING, AnalyseStatus.fromInt(-1))
    }

    @Test
    fun value_roundTripsForAllEntries() {
        AnalyseStatus.entries.forEach { status ->
            assertEquals(status, AnalyseStatus.fromInt(status.value))
        }
    }
}

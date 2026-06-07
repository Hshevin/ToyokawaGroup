package imgrecord.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyseTypeTest {

    @Test
    fun fromInt_returnsMatchingType() {
        assertEquals(AnalyseType.BUILDING, AnalyseType.fromInt(0))
        assertEquals(AnalyseType.ROAD, AnalyseType.fromInt(1))
    }

    @Test
    fun fromInt_unknownValue_defaultsToBuilding() {
        assertEquals(AnalyseType.BUILDING, AnalyseType.fromInt(99))
        assertEquals(AnalyseType.BUILDING, AnalyseType.fromInt(-1))
    }

    @Test
    fun value_roundTripsForAllEntries() {
        AnalyseType.entries.forEach { type ->
            assertEquals(type, AnalyseType.fromInt(type.value))
        }
    }
}

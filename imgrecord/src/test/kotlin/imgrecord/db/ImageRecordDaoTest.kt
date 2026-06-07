package imgrecord.db

import imgrecord.model.AnalyseStatus
import imgrecord.model.AnalyseType
import imgrecord.testutil.DatabaseTest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageRecordDaoTest : DatabaseTest() {

    private fun sampleEntity(
        localUrl: String = "/tmp/a/",
        imgUrl: String = "https://example.com/a.jpg",
        analyseType: Int = AnalyseType.BUILDING.value,
        status: Int = AnalyseStatus.PENDING.value,
        time: Long = 100L,
        summaryJson: String = "",
        errInfo: String? = null,
    ) = ImageRecordEntity(
        localUrl = localUrl,
        imgUrl = imgUrl,
        analyseType = analyseType,
        status = status,
        time = time,
        summaryJson = summaryJson,
        errInfo = errInfo,
    )

    @Test
    fun insertAndGetByLocalUrl() = runTest {
        val entity = sampleEntity()
        dao.insert(entity)

        assertEquals(entity, dao.getByLocalUrl("/tmp/a/"))
    }

    @Test
    fun getByLocalUrl_returnsNullWhenMissing() = runTest {
        assertNull(dao.getByLocalUrl("/missing/"))
    }

    @Test
    fun update_persistsChanges() = runTest {
        val entity = sampleEntity()
        dao.insert(entity)

        val updated = entity.copy(
            status = AnalyseStatus.DONE.value,
            time = 200L,
            summaryJson = """{"done":true}""",
        )
        dao.update(updated)

        assertEquals(updated, dao.getByLocalUrl(entity.localUrl))
    }

    @Test
    fun getAll_returnsAllRecords() = runTest {
        dao.insert(sampleEntity(localUrl = "/tmp/1/"))
        dao.insert(sampleEntity(localUrl = "/tmp/2/", imgUrl = "https://example.com/b.jpg"))

        assertEquals(2, dao.getAll().size)
    }

    @Test
    fun getByStatus_filtersRecords() = runTest {
        dao.insert(sampleEntity(localUrl = "/tmp/p/", status = AnalyseStatus.PENDING.value))
        dao.insert(sampleEntity(localUrl = "/tmp/d/", status = AnalyseStatus.DONE.value))
        dao.insert(sampleEntity(localUrl = "/tmp/f/", status = AnalyseStatus.FAILED.value, errInfo = "err"))

        assertEquals(1, dao.getByStatus(AnalyseStatus.PENDING.value).size)
        assertEquals(1, dao.getByStatus(AnalyseStatus.DONE.value).size)
        assertEquals(1, dao.getByStatus(AnalyseStatus.FAILED.value).size)
    }

    @Test
    fun deleteByLocalUrl_removesSingleRecord() = runTest {
        dao.insert(sampleEntity(localUrl = "/tmp/1/"))
        dao.insert(sampleEntity(localUrl = "/tmp/2/"))

        assertEquals(1, dao.deleteByLocalUrl("/tmp/1/"))
        assertEquals(1, dao.getAll().size)
        assertNull(dao.getByLocalUrl("/tmp/1/"))
    }

    @Test
    fun deleteByLocalUrl_returnsZeroWhenMissing() = runTest {
        assertEquals(0, dao.deleteByLocalUrl("/missing/"))
    }

    @Test
    fun deleteAll_clearsTable() = runTest {
        dao.insert(sampleEntity(localUrl = "/tmp/1/"))
        dao.insert(sampleEntity(localUrl = "/tmp/2/"))

        assertEquals(2, dao.deleteAll())
        assertTrue(dao.getAll().isEmpty())
    }

    @Test(expected = android.database.sqlite.SQLiteConstraintException::class)
    fun insert_duplicatePrimaryKey_throws() = runTest {
        val entity = sampleEntity()
        dao.insert(entity)
        dao.insert(entity)
    }
}

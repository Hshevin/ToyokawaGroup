package imgrecord

import imgrecord.db.ImageRecordEntity
import imgrecord.model.AnalyseResult
import imgrecord.model.AnalyseStatus
import imgrecord.model.AnalyseType
import imgrecord.testutil.DatabaseTest
import imgrecord.testutil.FakeImageAnalyser
import imgrecord.testutil.createRepository
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ImageRecordRepositoryTest : DatabaseTest() {

    private val tempRoot = System.getProperty("java.io.tmpdir") + "/imgrecord-test"

    @Test
    fun generateLocalUrl_usesPrefixAndEndsWithSeparator() {
        val prefix = tempRoot + "/prefix"
        val localUrl = ImageRecordRepository.generateLocalUrl(prefix)

        assertTrue(localUrl.startsWith(prefix))
        assertTrue(localUrl.endsWith(File.separator))
    }

    @Test
    fun generateLocalUrl_producesUniquePaths() {
        val prefix = tempRoot + "/unique"
        val first = ImageRecordRepository.generateLocalUrl(prefix)
        val second = ImageRecordRepository.generateLocalUrl(prefix)

        assertNotEquals(first, second)
    }

    @Test
    fun getAndSetLocalUrlPrefix() = runTest {
        val repo = createRepo(prefix = "/tmp/original")

        assertEquals("/tmp/original", repo.getLocalUrlPrefix())

        repo.setLocalUrlPrefix("/tmp/updated")
        assertEquals("/tmp/updated", repo.getLocalUrlPrefix())
    }

    @Test(expected = IllegalArgumentException::class)
    fun setLocalUrlPrefix_blank_throws() = runTest {
        createRepo(prefix = "/tmp/original").setLocalUrlPrefix("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun insert_blankPrefix_throws() = runTest {
        val repo = createRepository(
            dao = dao,
            prefix = "",
            analyser = FakeImageAnalyser.success(),
            scope = CoroutineScope(coroutineContext),
        )
        repo.insert("https://example.com/a.jpg", AnalyseType.BUILDING)
    }

    @Test
    fun insert_writesPendingRecordAndCreatesDirectory() = runTest {
        val repo = createRepo(prefix = tempRoot + "/insert")
        val imgUrl = "https://example.com/a.jpg"

        val localUrl = repo.insert(imgUrl, AnalyseType.BUILDING)

        assertTrue(File(localUrl).isDirectory)
        val record = repo.queryByLocalUrl(localUrl)
        assertNotNull(record)
        assertEquals(imgUrl, record!!.imgUrl)
        assertEquals(AnalyseType.BUILDING, record.analyseType)
        assertEquals(AnalyseStatus.PENDING, record.status)
        assertEquals("", record.summaryJson)
        assertNull(record.errInfo)
    }

    @Test
    fun insert_sameImgUrl_generatesDifferentLocalUrls() = runTest {
        val repo = createRepo(prefix = tempRoot + "/dup")
        val imgUrl = "https://example.com/same.jpg"

        val first = repo.insert(imgUrl, AnalyseType.ROAD)
        val second = repo.insert(imgUrl, AnalyseType.ROAD)

        assertNotEquals(first, second)
        assertEquals(2, repo.traverse().size)
    }

    @Test
    fun insert_backgroundAnalysis_updatesToDone() = runTest {
        val summary = """{"label":"building"}"""
        val analyser = FakeImageAnalyser.success(summaryJson = summary, time = 1234L)
        val repo = createRepo(prefix = tempRoot + "/bg-done", analyser = analyser)

        val localUrl = repo.insert("https://example.com/a.jpg", AnalyseType.BUILDING)
        advanceUntilIdle()

        val record = repo.queryByLocalUrl(localUrl)!!
        assertEquals(AnalyseStatus.DONE, record.status)
        assertEquals(1234L, record.time)
        assertEquals(summary, record.summaryJson)
        assertNull(record.errInfo)
        assertEquals(1, analyser.calls.size)
        assertEquals(localUrl, analyser.calls[0].first)
    }

    @Test
    fun analyseAndUpdate_failedResult_persistsErrInfo() = runTest {
        val analyser = FakeImageAnalyser.failed(errInfo = "model error", time = 5678L)
        val repo = createRepo(prefix = tempRoot + "/failed", analyser = analyser)
        val localUrl = repo.insert("https://example.com/a.jpg", AnalyseType.ROAD)
        advanceUntilIdle()

        val record = repo.queryByLocalUrl(localUrl)!!
        assertEquals(AnalyseStatus.FAILED, record.status)
        assertEquals(5678L, record.time)
        assertEquals("", record.summaryJson)
        assertEquals("model error", record.errInfo)
    }

    @Test
    fun analyseAndUpdate_analyserThrows_persistsFailedWithMessage() = runTest {
        val analyser = FakeImageAnalyser.throwing(IllegalStateException("network down"))
        val repo = createRepo(prefix = tempRoot + "/throw", analyser = analyser)
        val localUrl = repo.insert("https://example.com/a.jpg", AnalyseType.BUILDING)
        advanceUntilIdle()

        val record = repo.queryByLocalUrl(localUrl)!!
        assertEquals(AnalyseStatus.FAILED, record.status)
        assertEquals("network down", record.errInfo)
    }

    @Test
    fun analyseAndUpdate_missingRecord_isNoOp() = runTest {
        val analyser = FakeImageAnalyser.success()
        val repo = createRepo(prefix = tempRoot + "/missing", analyser = analyser)

        repo.analyseAndUpdate("/not/exists/")

        assertTrue(analyser.calls.isEmpty())
        assertTrue(repo.traverse().isEmpty())
    }

    @Test
    fun analyseAndUpdate_canRetryManually() = runTest {
        var attempt = 0
        val analyser = FakeImageAnalyser { _, _, _ ->
            attempt++
            if (attempt == 1) {
                AnalyseResult(
                    status = AnalyseStatus.FAILED,
                    time = 1L,
                    summaryJson = "",
                    errInfo = "temporary",
                )
            } else {
                AnalyseResult(
                    status = AnalyseStatus.DONE,
                    time = 2L,
                    summaryJson = """{"retry":true}""",
                )
            }
        }
        val repo = createRepo(prefix = tempRoot + "/retry", analyser = analyser)
        val localUrl = repo.insert("https://example.com/a.jpg", AnalyseType.BUILDING)
        advanceUntilIdle()

        assertEquals(AnalyseStatus.FAILED, repo.queryByLocalUrl(localUrl)!!.status)

        repo.analyseAndUpdate(localUrl)
        val record = repo.queryByLocalUrl(localUrl)!!
        assertEquals(AnalyseStatus.DONE, record.status)
        assertEquals("""{"retry":true}""", record.summaryJson)
        assertEquals(2, analyser.calls.size)
    }

    @Test
    fun setLocalUrlPrefix_affectsSubsequentInsert() = runTest {
        val repo = createRepo(prefix = tempRoot + "/first")
        val first = repo.insert("https://example.com/a.jpg", AnalyseType.BUILDING)

        val newPrefix = tempRoot + "/second"
        repo.setLocalUrlPrefix(newPrefix)
        val second = repo.insert("https://example.com/b.jpg", AnalyseType.ROAD)

        assertTrue(first.startsWith(tempRoot + "/first"))
        assertTrue(second.startsWith(newPrefix))
    }

    @Test
    fun queryByLocalUrl_returnsNullWhenMissing() = runTest {
        val repo = createRepo(prefix = tempRoot + "/query")
        assertNull(repo.queryByLocalUrl("/missing/"))
    }

    @Test
    fun queryByStatus_returnsPendingBeforeBackgroundCompletes() = runTest {
        val repo = createRepo(prefix = tempRoot + "/pending-query")
        val localUrl = repo.insert("https://example.com/p.jpg", AnalyseType.BUILDING)

        val pending = repo.queryByStatus(AnalyseStatus.PENDING)
        assertEquals(1, pending.size)
        assertEquals(localUrl, pending.single().localUrl)
    }

    @Test
    fun queryByStatus_returnsMatchingRecords() = runTest {
        val doneAnalyser = FakeImageAnalyser.success()
        val failAnalyser = FakeImageAnalyser.failed()
        val pendingRepo = createRepo(prefix = tempRoot + "/status-p", analyser = doneAnalyser)
        val failRepo = createRepo(prefix = tempRoot + "/status-f", analyser = failAnalyser)

        val pendingUrl = pendingRepo.insert("https://example.com/p.jpg", AnalyseType.BUILDING)
        failRepo.insert("https://example.com/f.jpg", AnalyseType.ROAD)
        advanceUntilIdle()

        assertEquals(1, pendingRepo.queryByStatus(AnalyseStatus.DONE).size)
        assertEquals(1, failRepo.queryByStatus(AnalyseStatus.FAILED).size)
        assertEquals(
            pendingUrl,
            pendingRepo.queryByStatus(AnalyseStatus.DONE).single().localUrl,
        )
    }

    @Test
    fun traverse_returnsAllRecords() = runTest {
        val repo = createRepo(prefix = tempRoot + "/traverse")
        repo.insert("https://example.com/1.jpg", AnalyseType.BUILDING)
        repo.insert("https://example.com/2.jpg", AnalyseType.ROAD)

        assertEquals(2, repo.traverse().size)
    }

    @Test
    fun delete_removesExistingRecord() = runTest {
        val repo = createRepo(prefix = tempRoot + "/delete")
        val localUrl = repo.insert("https://example.com/a.jpg", AnalyseType.BUILDING)

        assertTrue(repo.delete(localUrl))
        assertNull(repo.queryByLocalUrl(localUrl))
    }

    @Test
    fun delete_returnsFalseWhenMissing() = runTest {
        val repo = createRepo(prefix = tempRoot + "/delete-missing")
        assertFalse(repo.delete("/missing/"))
    }

    @Test
    fun analyseAndUpdate_analyserThrowsNullMessage_usesThrowableToString() = runTest {
        val analyser = FakeImageAnalyser.throwing(RuntimeException())
        val repo = createRepo(prefix = tempRoot + "/throw-null-msg", analyser = analyser)
        val localUrl = repo.insert("https://example.com/a.jpg", AnalyseType.BUILDING)
        advanceUntilIdle()

        val record = repo.queryByLocalUrl(localUrl)!!
        assertEquals(AnalyseStatus.FAILED, record.status)
        assertNotNull(record.errInfo)
        assertTrue(record.errInfo!!.contains("RuntimeException"))
    }

    @Test
    fun queryByLocalUrl_mapsUnknownDbEnumsToDefaults() = runTest {
        dao.insert(
            ImageRecordEntity(
                localUrl = "/tmp/unknown/",
                imgUrl = "https://example.com/x.jpg",
                analyseType = 999,
                status = 888,
                time = 1L,
                summaryJson = "",
                errInfo = null,
            ),
        )
        val repo = createRepo(prefix = tempRoot + "/unknown-enum")

        val record = repo.queryByLocalUrl("/tmp/unknown/")!!
        assertEquals(AnalyseType.BUILDING, record.analyseType)
        assertEquals(AnalyseStatus.PENDING, record.status)
    }

    @Test
    fun deleteAll_clearsAllRecords() = runTest {
        val repo = createRepo(prefix = tempRoot + "/delete-all")
        repo.insert("https://example.com/1.jpg", AnalyseType.BUILDING)
        repo.insert("https://example.com/2.jpg", AnalyseType.ROAD)

        assertEquals(2, repo.deleteAll())
        assertTrue(repo.traverse().isEmpty())
    }

    private fun createRepo(
        prefix: String,
        analyser: FakeImageAnalyser = FakeImageAnalyser.success(),
    ): ImageRecordRepository = createRepository(
        dao = dao,
        prefix = prefix,
        analyser = analyser,
        scope = CoroutineScope(coroutineContext),
    )
}

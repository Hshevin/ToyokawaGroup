package imgrecord.testutil

import imgrecord.ImageAnalyser
import imgrecord.ImageRecordRepository
import imgrecord.model.AnalyseResult
import imgrecord.model.AnalyseStatus
import imgrecord.model.AnalyseType
import kotlinx.coroutines.CoroutineScope

class FakeImageAnalyser(
    private val handler: suspend (localUrl: String, imgUrl: String, analyseType: AnalyseType) -> AnalyseResult,
) : ImageAnalyser {

    val calls = mutableListOf<Triple<String, String, AnalyseType>>()

    override suspend fun analyse(
        localUrl: String,
        imgUrl: String,
        analyseType: AnalyseType,
    ): AnalyseResult {
        calls.add(Triple(localUrl, imgUrl, analyseType))
        return handler(localUrl, imgUrl, analyseType)
    }

    companion object {
        fun success(
            summaryJson: String = """{"ok":true}""",
            time: Long = 1_700_000_000_000L,
        ) = FakeImageAnalyser { _, _, _ ->
            AnalyseResult(
                status = AnalyseStatus.DONE,
                time = time,
                summaryJson = summaryJson,
            )
        }

        fun failed(
            errInfo: String = "analysis failed",
            time: Long = 1_700_000_000_001L,
        ) = FakeImageAnalyser { _, _, _ ->
            AnalyseResult(
                status = AnalyseStatus.FAILED,
                time = time,
                summaryJson = "",
                errInfo = errInfo,
            )
        }

        fun throwing(error: Throwable) = FakeImageAnalyser { _, _, _ ->
            throw error
        }
    }
}

fun createRepository(
    dao: imgrecord.db.ImageRecordDao,
    prefix: String,
    analyser: ImageAnalyser,
    scope: CoroutineScope,
    autoAnalyse: Boolean = true,
) = ImageRecordRepository(
    dao = dao,
    localUrlPrefix = prefix,
    analyser = analyser,
    scope = scope,
    autoAnalyse = autoAnalyse,
)

package imgrecord

import android.content.Context
import imgrecord.db.ImageRecordDao
import imgrecord.db.ImageRecordDatabase
import imgrecord.db.ImageRecordEntity
import imgrecord.model.AnalyseResult
import imgrecord.model.AnalyseStatus
import imgrecord.model.AnalyseType
import imgrecord.model.ImageRecord
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class ImageRecordRepository(
    private val dao: ImageRecordDao,
    localUrlPrefix: String,
    private val analyser: ImageAnalyser,
    private val scope: CoroutineScope,
) {

    @Volatile
    private var localUrlPrefix: String = localUrlPrefix

    constructor(
        context: Context,
        localUrlPrefix: String,
        analyser: ImageAnalyser,
        scope: CoroutineScope,
    ) : this(
        dao = ImageRecordDatabase.create(context).imageRecordDao(),
        localUrlPrefix = localUrlPrefix,
        analyser = analyser,
        scope = scope,
    )

    fun getLocalUrlPrefix(): String = localUrlPrefix

    fun setLocalUrlPrefix(prefix: String) {
        require(prefix.isNotBlank()) { "localUrlPrefix must not be blank" }
        localUrlPrefix = prefix
    }

    suspend fun insert(imgUrl: String, analyseType: AnalyseType): String {
        val localUrl = generateLocalUrl(requireLocalUrlPrefix())
        return insertAt(localUrl, imgUrl, analyseType)
    }

    suspend fun insertAt(localUrl: String, imgUrl: String, analyseType: AnalyseType): String {
        File(localUrl).mkdirs()
        val now = System.currentTimeMillis()
        dao.insert(
            ImageRecordEntity(
                localUrl = localUrl,
                imgUrl = imgUrl,
                analyseType = analyseType.value,
                status = AnalyseStatus.PENDING.value,
                time = now,
                summaryJson = "",
                errInfo = null,
            ),
        )
        scope.launch { analyseAndUpdate(localUrl) }
        return localUrl
    }

    suspend fun analyseAndUpdate(localUrl: String) {
        val record = dao.getByLocalUrl(localUrl) ?: return
        val analyseType = AnalyseType.fromInt(record.analyseType)

        val result = try {
            analyser.analyse(record.localUrl, record.imgUrl, analyseType)
        } catch (e: Exception) {
            AnalyseResult(
                status = AnalyseStatus.FAILED,
                time = System.currentTimeMillis(),
                summaryJson = "",
                errInfo = e.message ?: e.toString(),
            )
        }

        dao.update(record.toUpdatedEntity(result))
    }

    suspend fun queryByLocalUrl(localUrl: String): ImageRecord? =
        dao.getByLocalUrl(localUrl)?.toModel()

    suspend fun queryByStatus(status: AnalyseStatus): List<ImageRecord> =
        dao.getByStatus(status.value).map { it.toModel() }

    suspend fun traverse(): List<ImageRecord> =
        dao.getAll().map { it.toModel() }

    suspend fun delete(localUrl: String): Boolean =
        dao.deleteByLocalUrl(localUrl) > 0

    suspend fun deleteAll(): Int =
        dao.deleteAll()

    companion object {
        fun generateLocalUrl(prefix: String): String {
            val dir = File(prefix, UUID.randomUUID().toString())
            return dir.absolutePath + File.separator
        }
    }

    private fun requireLocalUrlPrefix(): String {
        val prefix = localUrlPrefix
        require(prefix.isNotBlank()) { "localUrlPrefix is not set" }
        return prefix
    }
}

private fun ImageRecordEntity.toUpdatedEntity(result: AnalyseResult): ImageRecordEntity =
    copy(
        status = result.status.value,
        time = result.time,
        summaryJson = result.summaryJson,
        errInfo = result.errInfo,
    )

private fun ImageRecordEntity.toModel(): ImageRecord =
    ImageRecord(
        localUrl = localUrl,
        imgUrl = imgUrl,
        analyseType = AnalyseType.fromInt(analyseType),
        status = AnalyseStatus.fromInt(status),
        time = time,
        summaryJson = summaryJson,
        errInfo = errInfo,
    )

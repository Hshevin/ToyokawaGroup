package imgrecord

import imgrecord.model.AnalyseResult
import imgrecord.model.AnalyseType

fun interface ImageAnalyser {
    suspend fun analyse(
        localUrl: String,
        imgUrl: String,
        analyseType: AnalyseType,
    ): AnalyseResult
}

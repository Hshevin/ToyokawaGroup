package imgrecord.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "image_record")
data class ImageRecordEntity(
    @PrimaryKey
    @ColumnInfo(name = "local_url")
    val localUrl: String,
    @ColumnInfo(name = "img_url")
    val imgUrl: String,
    @ColumnInfo(name = "analyse_type")
    val analyseType: Int,
    @ColumnInfo(name = "status")
    val status: Int,
    @ColumnInfo(name = "time")
    val time: Long,
    @ColumnInfo(name = "summary_json")
    val summaryJson: String,
    @ColumnInfo(name = "err_info")
    val errInfo: String?,
)

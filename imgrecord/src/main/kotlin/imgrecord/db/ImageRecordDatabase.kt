package imgrecord.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ImageRecordEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class ImageRecordDatabase : RoomDatabase() {

    abstract fun imageRecordDao(): ImageRecordDao

    companion object {
        private const val DB_NAME = "image_record.db"

        fun create(context: Context): ImageRecordDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                ImageRecordDatabase::class.java,
                DB_NAME,
            ).build()
    }
}

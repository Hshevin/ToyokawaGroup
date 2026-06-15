package imgrecord.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ImageRecordEntity::class, TaskEntity::class, AnomalyEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class ImageRecordDatabase : RoomDatabase() {

    abstract fun imageRecordDao(): ImageRecordDao
    abstract fun taskDao(): TaskDao
    abstract fun anomalyDao(): AnomalyDao

    companion object {
        private const val DB_NAME = "image_record.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE image_record ADD COLUMN task_id TEXT")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS task (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        scene_type TEXT NOT NULL,
                        area_name TEXT NOT NULL,
                        status TEXT NOT NULL,
                        priority TEXT NOT NULL,
                        operator TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS anomaly (
                        id TEXT NOT NULL PRIMARY KEY,
                        task_id TEXT NOT NULL,
                        image_local_url TEXT,
                        bbox_json TEXT NOT NULL,
                        anomaly_type TEXT NOT NULL,
                        review_status TEXT NOT NULL,
                        building_code TEXT NOT NULL,
                        location TEXT NOT NULL,
                        source TEXT NOT NULL,
                        comment TEXT NOT NULL,
                        severity TEXT NOT NULL,
                        thumbnail_path TEXT NOT NULL DEFAULT '',
                        photo_paths_json TEXT NOT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_anomaly_task_id ON anomaly(task_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_anomaly_image_local_url ON anomaly(image_local_url)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE anomaly ADD COLUMN thumbnail_path TEXT NOT NULL DEFAULT ''")
            }
        }

        @Volatile
        private var INSTANCE: ImageRecordDatabase? = null

        fun create(context: Context): ImageRecordDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ImageRecordDatabase::class.java,
                    DB_NAME,
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}

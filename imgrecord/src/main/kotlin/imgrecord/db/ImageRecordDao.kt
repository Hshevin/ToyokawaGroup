package imgrecord.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ImageRecordDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entity: ImageRecordEntity)

    @Update
    suspend fun update(entity: ImageRecordEntity)

    @Query("SELECT * FROM image_record")
    suspend fun getAll(): List<ImageRecordEntity>

    @Query("SELECT * FROM image_record WHERE local_url = :localUrl LIMIT 1")
    suspend fun getByLocalUrl(localUrl: String): ImageRecordEntity?

    @Query("SELECT * FROM image_record WHERE status = :status")
    suspend fun getByStatus(status: Int): List<ImageRecordEntity>

    @Query("SELECT * FROM image_record WHERE task_id = :taskId")
    suspend fun getByTaskId(taskId: String): List<ImageRecordEntity>

    @Query("DELETE FROM image_record WHERE local_url = :localUrl")
    suspend fun deleteByLocalUrl(localUrl: String): Int

    @Query("DELETE FROM image_record")
    suspend fun deleteAll(): Int
}

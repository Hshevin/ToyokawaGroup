package imgrecord.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface AnomalyDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AnomalyEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AnomalyEntity>)

    @Update
    suspend fun update(entity: AnomalyEntity)

    @Query("SELECT * FROM anomaly WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): AnomalyEntity?

    @Query("SELECT * FROM anomaly WHERE task_id = :taskId ORDER BY created_at ASC")
    suspend fun getByTaskId(taskId: String): List<AnomalyEntity>

    @Query("SELECT * FROM anomaly WHERE image_local_url = :imageLocalUrl ORDER BY created_at ASC")
    suspend fun getByImageLocalUrl(imageLocalUrl: String): List<AnomalyEntity>

    @Query("SELECT COUNT(*) FROM anomaly WHERE task_id = :taskId")
    suspend fun countByTaskId(taskId: String): Int

    @Query("DELETE FROM anomaly WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

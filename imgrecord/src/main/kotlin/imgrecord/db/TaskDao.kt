package imgrecord.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface TaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TaskEntity)

    @Update
    suspend fun update(entity: TaskEntity)

    @Query("SELECT * FROM task ORDER BY updated_at DESC")
    suspend fun getAll(): List<TaskEntity>

    @Query("SELECT * FROM task WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): TaskEntity?

    @Query("DELETE FROM task WHERE id = :id")
    suspend fun deleteById(id: String): Int
}

package com.ff.compress

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Insert
    suspend fun insert(t: TaskEntity): Long

    @Update
    suspend fun update(t: TaskEntity)

    @Delete
    suspend fun delete(t: TaskEntity)

    @Query("SELECT * FROM tasks ORDER BY id DESC")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE status = 'PENDING' ORDER BY id ASC LIMIT 1")
    suspend fun nextPending(): TaskEntity?

    @Query("SELECT COUNT(*) FROM tasks WHERE status = 'PENDING'")
    suspend fun pendingCount(): Int

    /** 只更新运行中任务的进度，原子防止迟到写入覆盖最终状态 */
    @Query("UPDATE tasks SET progress = :p WHERE id = :id AND status = 'RUNNING'")
    suspend fun updateProgress(id: Long, p: Float)

    @Query("UPDATE tasks SET status = 'PENDING', progress = 0, error = NULL, finishedAt = NULL WHERE id = :id")
    suspend fun reset(id: Long)

    @Query("DELETE FROM tasks WHERE status IN ('DONE', 'FAILED', 'CANCELLED')")
    suspend fun clearFinished()
}

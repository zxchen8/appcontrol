package com.plearn.appcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.plearn.appcontrol.data.local.entity.TaskRunEntity

@Dao
interface TaskRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(taskRun: TaskRunEntity)

    @Query("SELECT * FROM task_runs WHERE taskId = :taskId ORDER BY startedAt DESC LIMIT 1")
    suspend fun findLatestByTaskId(taskId: String): TaskRunEntity?

    @Query("SELECT * FROM task_runs WHERE sessionId = :sessionId ORDER BY cycleNo ASC, startedAt ASC")
    suspend fun findBySessionId(sessionId: String): List<TaskRunEntity>
}
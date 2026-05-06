package com.plearn.appcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.plearn.appcontrol.data.local.entity.TaskDefinitionEntity

@Dao
interface TaskDefinitionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(taskDefinition: TaskDefinitionEntity)

    @Query("SELECT * FROM task_definitions ORDER BY updatedAt DESC")
    suspend fun getAll(): List<TaskDefinitionEntity>

    @Query("SELECT * FROM task_definitions WHERE taskId = :taskId LIMIT 1")
    suspend fun getByTaskId(taskId: String): TaskDefinitionEntity?

    @Query("UPDATE task_definitions SET definitionStatus = :definitionStatus, updatedAt = :updatedAt WHERE taskId = :taskId")
    suspend fun updateDefinitionStatus(taskId: String, definitionStatus: String, updatedAt: Long)

    @Query("UPDATE task_definitions SET enabled = :enabled, updatedAt = :updatedAt WHERE taskId = :taskId")
    suspend fun updateEnabled(taskId: String, enabled: Boolean, updatedAt: Long)
}
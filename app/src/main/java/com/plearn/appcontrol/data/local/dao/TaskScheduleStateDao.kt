package com.plearn.appcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.plearn.appcontrol.data.local.entity.TaskScheduleStateEntity

@Dao
interface TaskScheduleStateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(taskScheduleState: TaskScheduleStateEntity)

    @Query("SELECT * FROM task_schedule_states WHERE taskId = :taskId LIMIT 1")
    suspend fun getByTaskId(taskId: String): TaskScheduleStateEntity?

    @Query("UPDATE task_schedule_states SET nextTriggerAt = :nextTriggerAt, standbyEnabled = :standbyEnabled, lastTriggerAt = :lastTriggerAt, lastScheduleStatus = :lastScheduleStatus WHERE taskId = :taskId")
    suspend fun updateScheduleState(
        taskId: String,
        nextTriggerAt: Long?,
        standbyEnabled: Boolean,
        lastTriggerAt: Long?,
        lastScheduleStatus: String?,
    )
}
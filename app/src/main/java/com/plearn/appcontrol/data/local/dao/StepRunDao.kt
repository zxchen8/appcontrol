package com.plearn.appcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.plearn.appcontrol.data.local.entity.StepRunEntity

@Dao
interface StepRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stepRuns: List<StepRunEntity>)

    @Query("SELECT * FROM step_runs WHERE runId = :runId ORDER BY startedAt ASC")
    suspend fun findByRunId(runId: String): List<StepRunEntity>
}
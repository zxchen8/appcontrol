package com.plearn.appcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.plearn.appcontrol.data.local.entity.ContinuousSessionEntity

@Dao
interface ContinuousSessionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(session: ContinuousSessionEntity)

    @Query("SELECT * FROM continuous_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun findBySessionId(sessionId: String): ContinuousSessionEntity?

    @Query("SELECT * FROM continuous_sessions WHERE taskId = :taskId AND status = 'running' LIMIT 1")
    suspend fun findRunningSessionByTaskId(taskId: String): ContinuousSessionEntity?

    @Query("SELECT * FROM continuous_sessions WHERE status = 'running' ORDER BY startedAt DESC")
    suspend fun findRunningSessions(): List<ContinuousSessionEntity>

    @Query("UPDATE continuous_sessions SET status = :status, finishedAt = :finishedAt, totalCycles = :totalCycles, successCycles = :successCycles, failedCycles = :failedCycles, lastErrorCode = :lastErrorCode WHERE sessionId = :sessionId")
    suspend fun updateTerminalState(
        sessionId: String,
        status: String,
        finishedAt: Long?,
        totalCycles: Int,
        successCycles: Int,
        failedCycles: Int,
        lastErrorCode: String?,
    ): Int
}
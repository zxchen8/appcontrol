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

    @Query("SELECT * FROM task_runs WHERE taskId = :taskId ORDER BY startedAt DESC, runId DESC LIMIT 1")
    suspend fun findLatestByTaskId(taskId: String): TaskRunEntity?

    @Query("SELECT * FROM task_runs ORDER BY startedAt DESC, runId DESC LIMIT :limit")
    suspend fun listRecent(limit: Int): List<TaskRunEntity>

    @Query("SELECT * FROM task_runs WHERE taskId = :taskId ORDER BY startedAt DESC, runId DESC LIMIT :limit")
    suspend fun listRecentByTaskId(taskId: String, limit: Int): List<TaskRunEntity>

    @Query("SELECT * FROM task_runs WHERE sessionId = :sessionId ORDER BY cycleNo ASC, startedAt ASC, runId ASC")
    suspend fun findBySessionId(sessionId: String): List<TaskRunEntity>

    @Query("SELECT DISTINCT taskId FROM task_runs")
    suspend fun listTaskIds(): List<String>

    @Query("SELECT COUNT(*) FROM task_runs")
    suspend fun countAll(): Int

    @Query(
        """
        SELECT COALESCE(
            SUM(
                length(CAST(COALESCE(errorCode, '') AS BLOB)) +
                length(CAST(COALESCE(message, '') AS BLOB)) +
                length(CAST(artifactsJson AS BLOB))
            ),
            0
        )
        FROM task_runs
        """,
    )
    suspend fun estimateDiagnosticsStorageBytes(): Long

    @Query(
        """
        SELECT runId
        FROM task_runs
        WHERE (:protectedRunId IS NULL OR runId != :protectedRunId)
        ORDER BY startedAt ASC, runId ASC
        LIMIT 1
        """,
    )
    suspend fun findOldestRunId(protectedRunId: String? = null): String?

    @Query("DELETE FROM task_runs WHERE startedAt < :startedBefore")
    suspend fun deleteStartedBefore(startedBefore: Long)

    @Query("DELETE FROM task_runs WHERE startedAt < :startedBefore AND runId != :protectedRunId")
    suspend fun deleteStartedBefore(startedBefore: Long, protectedRunId: String)

    @Query("DELETE FROM task_runs WHERE runId = :runId")
    suspend fun deleteByRunId(runId: String)

    @Query(
        """
        DELETE FROM task_runs
        WHERE runId IN (
            SELECT runId FROM (
                SELECT runId
                FROM task_runs
                WHERE taskId = :taskId
                ORDER BY startedAt DESC, runId DESC
                LIMIT -1 OFFSET :retainCount
            )
        )
        """,
    )
    suspend fun deleteAllExceptMostRecent(taskId: String, retainCount: Int)

    @Query(
        """
        DELETE FROM task_runs
        WHERE runId != :protectedRunId
        AND runId IN (
            SELECT runId FROM (
                SELECT runId
                FROM task_runs
                WHERE taskId = :taskId
                ORDER BY startedAt DESC, runId DESC
                LIMIT -1 OFFSET :retainCount
            )
        )
        """,
    )
    suspend fun deleteAllExceptMostRecent(taskId: String, retainCount: Int, protectedRunId: String)
}
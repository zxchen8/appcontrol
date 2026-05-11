package com.plearn.appcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.plearn.appcontrol.data.local.entity.DiagnosticsEventEntity

@Dao
interface DiagnosticsEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(event: DiagnosticsEventEntity): Long

    @Query("SELECT DISTINCT taskId FROM diagnostic_events WHERE taskId IS NOT NULL")
    suspend fun listTaskIds(): List<String>

    @Query(
        """
        SELECT *
        FROM diagnostic_events
        WHERE taskId = :taskId OR taskId IS NULL
        ORDER BY createdAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun listRecentForTask(taskId: String, limit: Int): List<DiagnosticsEventEntity>

    @Query(
        """
        SELECT id, taskId, runId, createdAt, eventType, payloadJson
        FROM (
            SELECT id, taskId, runId, createdAt, eventType, payloadJson, 0 AS scopePriority
            FROM diagnostic_events
            WHERE taskId = :taskId AND runId = :runId

            UNION ALL

            SELECT id, taskId, runId, createdAt, eventType, payloadJson, 1 AS scopePriority
            FROM diagnostic_events
            WHERE taskId IS NULL OR (taskId = :taskId AND runId IS NULL)
        )
        ORDER BY scopePriority ASC, createdAt DESC, id DESC
        LIMIT :limit
        """,
    )
    suspend fun listRecentForTaskAndRun(taskId: String, runId: String, limit: Int): List<DiagnosticsEventEntity>

    @Query(
        """
        SELECT COALESCE(
            SUM(
                length(CAST(COALESCE(taskId, '') AS BLOB)) +
                length(CAST(COALESCE(runId, '') AS BLOB)) +
                length(CAST(eventType AS BLOB)) +
                length(CAST(payloadJson AS BLOB))
            ),
            0
        )
        FROM diagnostic_events
        """,
    )
    suspend fun estimateDiagnosticsStorageBytes(): Long

    @Query(
        """
        SELECT id
        FROM diagnostic_events
        WHERE (:protectedId IS NULL OR id != :protectedId)
        ORDER BY createdAt ASC, id ASC
        LIMIT 1
        """,
    )
    suspend fun findOldestId(protectedId: Long? = null): Long?

    @Query("DELETE FROM diagnostic_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query(
        "DELETE FROM diagnostic_events WHERE runId = :runId AND eventType = :eventType AND ((taskId IS NULL AND :taskId IS NULL) OR taskId = :taskId)",
    )
    suspend fun deleteRunScopedEvent(taskId: String?, runId: String, eventType: String)

    @Query(
        """
        DELETE FROM diagnostic_events
        WHERE taskId = :taskId
        AND id IN (
            SELECT id FROM (
                SELECT id
                FROM diagnostic_events
                WHERE taskId = :taskId
                ORDER BY createdAt DESC, id DESC
                LIMIT -1 OFFSET :retainCount
            )
        )
        """,
    )
    suspend fun deleteAllExceptMostRecentForTask(taskId: String, retainCount: Int)

    @Query(
        """
        DELETE FROM diagnostic_events
        WHERE taskId IS NULL
        AND id IN (
            SELECT id FROM (
                SELECT id
                FROM diagnostic_events
                WHERE taskId IS NULL
                ORDER BY createdAt DESC, id DESC
                LIMIT -1 OFFSET :retainCount
            )
        )
        """,
    )
    suspend fun deleteAllExceptMostRecentGlobal(retainCount: Int)

    @Query(
        "DELETE FROM diagnostic_events WHERE runId IS NOT NULL AND (:protectedRunId IS NULL OR runId != :protectedRunId) AND runId NOT IN (SELECT runId FROM task_runs)",
    )
    suspend fun deleteOrphanedRunScopedEvents(protectedRunId: String? = null)

    @Query(
        "DELETE FROM diagnostic_events WHERE taskId IS NOT NULL AND taskId NOT IN (SELECT taskId FROM task_definitions)",
    )
    suspend fun deleteOrphanedTaskScopedEvents()

    @Query("DELETE FROM diagnostic_events WHERE createdAt < :createdBefore")
    suspend fun deleteCreatedBefore(createdBefore: Long)
}
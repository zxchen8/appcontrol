package com.plearn.appcontrol.data.repository

import androidx.room.withTransaction
import com.plearn.appcontrol.data.local.AppControlDatabase
import com.plearn.appcontrol.data.local.dao.ContinuousSessionDao
import com.plearn.appcontrol.data.local.dao.StepRunDao
import com.plearn.appcontrol.data.local.dao.TaskRunDao
import com.plearn.appcontrol.data.model.ContinuousSessionRecord
import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.data.model.TaskRunRecord

/**
 * Terminal session fields grouped for [ContinuousSessionPersistenceRepository.recordTerminalCycle].
 */
data class TerminalSessionUpdate(
    val sessionId: String,
    val status: String,
    val finishedAt: Long,
    val totalCycles: Int,
    val successCycles: Int,
    val failedCycles: Int,
    val lastErrorCode: String?,
)

/**
 * Transaction-aware coordinator for continuous-session persistence.
 *
 * Owns the atomic boundary that records task/step runs together with
 * session-state mutations so the two are never out of sync.
 */
interface ContinuousSessionPersistenceRepository {
    /**
     * Atomically persists a cycle's task run, step runs, and the updated
     * session record (cursor advance, cycle counters, credential rotation).
     */
    suspend fun recordCycleProgress(
        taskRun: TaskRunRecord,
        stepRuns: List<StepRunRecord>,
        session: ContinuousSessionRecord,
    )

    /**
     * Atomically persists a cycle's task run, step runs, and terminates
     * the session (status, finishedAt, final counters, last error code).
     *
     * @throws IllegalStateException if [terminalUpdate] references a session
     *   that does not exist, ensuring run rows are never committed without
     *   the matching session mutation.
     */
    suspend fun recordTerminalCycle(
        taskRun: TaskRunRecord,
        stepRuns: List<StepRunRecord>,
        terminalUpdate: TerminalSessionUpdate,
    )
}

class RoomContinuousSessionPersistenceRepository(
    private val database: AppControlDatabase,
    private val taskRunDao: TaskRunDao,
    private val stepRunDao: StepRunDao,
    private val continuousSessionDao: ContinuousSessionDao,
) : ContinuousSessionPersistenceRepository {

    override suspend fun recordCycleProgress(
        taskRun: TaskRunRecord,
        stepRuns: List<StepRunRecord>,
        session: ContinuousSessionRecord,
    ) {
        database.withTransaction {
            taskRunDao.upsert(taskRun.toEntity())
            if (stepRuns.isNotEmpty()) {
                stepRunDao.insertAll(stepRuns.map { it.toEntity() })
            }
            continuousSessionDao.upsert(session.toEntity())
        }
    }

    override suspend fun recordTerminalCycle(
        taskRun: TaskRunRecord,
        stepRuns: List<StepRunRecord>,
        terminalUpdate: TerminalSessionUpdate,
    ) {
        database.withTransaction {
            taskRunDao.upsert(taskRun.toEntity())
            if (stepRuns.isNotEmpty()) {
                stepRunDao.insertAll(stepRuns.map { it.toEntity() })
            }
            val updatedRows = continuousSessionDao.updateTerminalState(
                sessionId = terminalUpdate.sessionId,
                status = terminalUpdate.status,
                finishedAt = terminalUpdate.finishedAt,
                totalCycles = terminalUpdate.totalCycles,
                successCycles = terminalUpdate.successCycles,
                failedCycles = terminalUpdate.failedCycles,
                lastErrorCode = terminalUpdate.lastErrorCode,
            )
            check(updatedRows > 0) {
                "recordTerminalCycle: session ${terminalUpdate.sessionId} not found; " +
                    "transaction rolled back to prevent orphaned run ${taskRun.runId}"
            }
        }
    }
}

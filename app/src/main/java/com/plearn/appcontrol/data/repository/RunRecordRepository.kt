package com.plearn.appcontrol.data.repository

import androidx.room.withTransaction
import com.plearn.appcontrol.data.local.AppControlDatabase
import com.plearn.appcontrol.data.local.dao.StepRunDao
import com.plearn.appcontrol.data.local.dao.TaskRunDao
import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import java.util.concurrent.TimeUnit

data class DiagnosticsRetentionPolicy(
    val maxRunsPerTask: Int = DEFAULT_MAX_RUNS_PER_TASK,
    val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    val maxStorageBytes: Long = DEFAULT_MAX_STORAGE_BYTES,
    val captureHighWatermarkBytes: Long = DEFAULT_CAPTURE_HIGH_WATERMARK_BYTES,
) {
    init {
        require(maxRunsPerTask >= 0) { "maxRunsPerTask must be >= 0" }
        require(maxAgeMs >= 0) { "maxAgeMs must be >= 0" }
        require(maxStorageBytes >= 0) { "maxStorageBytes must be >= 0" }
        require(captureHighWatermarkBytes >= 0) { "captureHighWatermarkBytes must be >= 0" }
        require(captureHighWatermarkBytes <= maxStorageBytes) {
            "captureHighWatermarkBytes must be <= maxStorageBytes"
        }
    }

    companion object {
        const val DEFAULT_MAX_RUNS_PER_TASK: Int = 500
        val DEFAULT_MAX_AGE_MS: Long = TimeUnit.DAYS.toMillis(14)
        const val DEFAULT_MAX_STORAGE_BYTES: Long = 512L * 1024L * 1024L
        const val DEFAULT_CAPTURE_HIGH_WATERMARK_BYTES: Long = DEFAULT_MAX_STORAGE_BYTES * 9L / 10L
    }
}

interface RunRecordRepository {
    suspend fun upsertTaskRun(taskRun: TaskRunRecord)
    suspend fun findLatestTaskRun(taskId: String): TaskRunRecord?
    suspend fun listRecentTaskRuns(limit: Int): List<TaskRunRecord>
    suspend fun listRecentTaskRunsByTaskId(taskId: String, limit: Int): List<TaskRunRecord>
    suspend fun findTaskRunsBySession(sessionId: String): List<TaskRunRecord>
    suspend fun insertStepRuns(stepRuns: List<StepRunRecord>)
    suspend fun findStepRuns(runId: String): List<StepRunRecord>
    suspend fun canCaptureFailureArtifact(): Boolean = true

    suspend fun recordTaskRun(taskRun: TaskRunRecord, stepRuns: List<StepRunRecord>) {
        upsertTaskRun(taskRun)
        if (stepRuns.isNotEmpty()) {
            insertStepRuns(stepRuns)
        }
    }
}

class RoomRunRecordRepository(
    private val database: AppControlDatabase,
    private val taskRunDao: TaskRunDao,
    private val stepRunDao: StepRunDao,
    private val retentionPolicy: DiagnosticsRetentionPolicy = DiagnosticsRetentionPolicy(),
    private val nowMsProvider: () -> Long = System::currentTimeMillis,
) : RunRecordRepository {
    override suspend fun upsertTaskRun(taskRun: TaskRunRecord) {
        taskRunDao.upsert(taskRun.toEntity())
    }

    override suspend fun findLatestTaskRun(taskId: String): TaskRunRecord? =
        taskRunDao.findLatestByTaskId(taskId)?.toRecord()

    override suspend fun listRecentTaskRuns(limit: Int): List<TaskRunRecord> =
        taskRunDao.listRecent(normalizeLimit(limit)).map { it.toRecord() }

    override suspend fun listRecentTaskRunsByTaskId(taskId: String, limit: Int): List<TaskRunRecord> =
        taskRunDao.listRecentByTaskId(taskId, normalizeLimit(limit)).map { it.toRecord() }

    override suspend fun findTaskRunsBySession(sessionId: String): List<TaskRunRecord> =
        taskRunDao.findBySessionId(sessionId).map { it.toRecord() }

    override suspend fun insertStepRuns(stepRuns: List<StepRunRecord>) {
        stepRunDao.insertAll(stepRuns.map { it.toEntity() })
    }

    override suspend fun findStepRuns(runId: String): List<StepRunRecord> =
        stepRunDao.findByRunId(runId).map { it.toRecord() }

    override suspend fun canCaptureFailureArtifact(): Boolean = database.withTransaction {
        pruneRetainedRunsForStartup()
        currentDiagnosticsStorageBytes() < retentionPolicy.captureHighWatermarkBytes
    }

    override suspend fun recordTaskRun(taskRun: TaskRunRecord, stepRuns: List<StepRunRecord>) {
        database.withTransaction {
            taskRunDao.upsert(taskRun.toEntity())
            if (stepRuns.isNotEmpty()) {
                stepRunDao.insertAll(stepRuns.map { it.toEntity() })
            }
            pruneRetainedRuns(taskRun)
        }
    }

    suspend fun pruneRetainedRunsAtStartup() {
        database.withTransaction {
            pruneRetainedRunsForStartup()
        }
    }

    private fun normalizeLimit(limit: Int): Int = limit.coerceAtLeast(0)

    private suspend fun pruneRetainedRuns(taskRun: TaskRunRecord) {
        val cutoffStartedAt = (nowMsProvider() - retentionPolicy.maxAgeMs).coerceAtLeast(0L)
        taskRunDao.deleteStartedBefore(cutoffStartedAt, taskRun.runId)
        taskRunDao.deleteAllExceptMostRecent(
            taskId = taskRun.taskId,
            retainCount = normalizeLimit(retentionPolicy.maxRunsPerTask),
            protectedRunId = taskRun.runId,
        )
        pruneRunsBeyondStorageBudget(protectedRunId = taskRun.runId)
    }

    private suspend fun pruneRetainedRunsForStartup() {
        val cutoffStartedAt = (nowMsProvider() - retentionPolicy.maxAgeMs).coerceAtLeast(0L)
        taskRunDao.deleteStartedBefore(cutoffStartedAt)
        taskRunDao.listTaskIds().forEach { taskId ->
            taskRunDao.deleteAllExceptMostRecent(taskId, normalizeLimit(retentionPolicy.maxRunsPerTask))
        }
        pruneRunsBeyondStorageBudget()
    }

    private suspend fun pruneRunsBeyondStorageBudget(protectedRunId: String? = null) {
        while (currentDiagnosticsStorageBytes() > retentionPolicy.maxStorageBytes) {
            val oldestRunId = taskRunDao.findOldestRunId(protectedRunId) ?: break
            taskRunDao.deleteByRunId(oldestRunId)
        }
    }

    private suspend fun currentDiagnosticsStorageBytes(): Long =
        taskRunDao.estimateDiagnosticsStorageBytes() + stepRunDao.estimateDiagnosticsStorageBytes()
}
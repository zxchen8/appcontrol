package com.plearn.appcontrol.runner

import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.data.repository.RunRecordRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskExecutionRecorderTest {
    @Test
    fun repositoryBackedRecorderShouldPersistExecutionViaAtomicRepositoryPath() = runBlocking {
        val repository = RecordingRunRecordRepository()
        val recorder = RepositoryBackedTaskExecutionRecorder(repository)
        val result = TaskExecutionResult(
            taskRun = TaskRunRecord(
                runId = "run-a",
                sessionId = null,
                cycleNo = null,
                taskId = "task-a",
                credentialSetId = null,
                credentialProfileId = null,
                credentialAlias = null,
                status = TaskRunStatus.FAILED,
                startedAt = 100L,
                finishedAt = 180L,
                durationMs = 80L,
                triggerType = RunTriggerType.CRON,
                errorCode = "RUNNER_STEP_FAILED",
                message = "step failed",
                artifactsJson = "{}",
            ),
            stepRuns = listOf(
                StepRunRecord(
                    runId = "stale-run-id",
                    stepId = "step-1",
                    status = StepRunStatus.FAILED,
                    startedAt = 110L,
                    finishedAt = 120L,
                    durationMs = 10L,
                    errorCode = "RUNNER_STEP_FAILED",
                    message = "tap failed",
                    artifactsJson = "{}",
                ),
            ),
            taskAttemptCount = 1,
        )

        val persisted = recorder.record(result, sessionId = "session-a", cycleNo = 2)

        assertEquals(1, repository.recordCallCount)
        assertEquals(0, repository.upsertTaskRunCallCount)
        assertEquals(0, repository.insertStepRunsCallCount)
        assertEquals("session-a", repository.recordedTaskRun?.sessionId)
        assertEquals(2, repository.recordedTaskRun?.cycleNo)
        assertEquals(listOf("run-a"), repository.recordedStepRuns.map { it.runId })
        assertEquals("session-a", persisted.taskRun.sessionId)
        assertEquals(2, persisted.taskRun.cycleNo)
        assertEquals(listOf("run-a"), persisted.stepRuns.map { it.runId })
    }

    private class RecordingRunRecordRepository : RunRecordRepository {
        var upsertTaskRunCallCount: Int = 0
        var insertStepRunsCallCount: Int = 0
        var recordCallCount: Int = 0
        var recordedTaskRun: TaskRunRecord? = null
        var recordedStepRuns: List<StepRunRecord> = emptyList()

        override suspend fun upsertTaskRun(taskRun: TaskRunRecord) {
            upsertTaskRunCallCount += 1
        }

        override suspend fun findLatestTaskRun(taskId: String): TaskRunRecord? = null

        override suspend fun listRecentTaskRuns(limit: Int): List<TaskRunRecord> = emptyList()

        override suspend fun listRecentTaskRunsByTaskId(taskId: String, limit: Int): List<TaskRunRecord> = emptyList()

        override suspend fun findTaskRunsBySession(sessionId: String): List<TaskRunRecord> = emptyList()

        override suspend fun insertStepRuns(stepRuns: List<StepRunRecord>) {
            insertStepRunsCallCount += 1
        }

        override suspend fun findStepRuns(runId: String): List<StepRunRecord> = emptyList()

        override suspend fun recordTaskRun(taskRun: TaskRunRecord, stepRuns: List<StepRunRecord>) {
            recordCallCount += 1
            recordedTaskRun = taskRun
            recordedStepRuns = stepRuns
        }
    }
}
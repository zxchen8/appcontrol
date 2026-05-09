package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.data.model.ContinuousSessionRecord
import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskScheduleStateRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.data.repository.RunRecordRepository
import com.plearn.appcontrol.data.repository.SessionRepository
import com.plearn.appcontrol.data.repository.TaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppDashboardServiceTest {
    @Test
    fun shouldBuildTaskItemsWithScheduleStateLatestRunAndRunningSession() = runBlocking {
        val service = AppDashboardService(
            taskRepository = FakeTaskRepository(
                definitions = listOf(
                    TaskDefinitionRecord(
                        taskId = "task-a",
                        name = "任务 A",
                        enabled = true,
                        triggerType = "cron",
                        definitionStatus = "ready",
                        rawJson = "{}",
                        updatedAt = 100L,
                    ),
                    TaskDefinitionRecord(
                        taskId = "task-b",
                        name = "任务 B",
                        enabled = false,
                        triggerType = "continuous",
                        definitionStatus = "draft",
                        rawJson = "{}",
                        updatedAt = 90L,
                    ),
                ),
                scheduleStates = mapOf(
                    "task-a" to TaskScheduleStateRecord(
                        taskId = "task-a",
                        nextTriggerAt = 5_000L,
                        standbyEnabled = true,
                        lastTriggerAt = 1_000L,
                        lastScheduleStatus = "scheduled",
                    ),
                ),
            ),
            runRecordRepository = FakeRunRecordRepository(
                latestByTaskId = mapOf(
                    "task-a" to TaskRunRecord(
                        runId = "run-a",
                        sessionId = null,
                        cycleNo = null,
                        taskId = "task-a",
                        credentialSetId = null,
                        credentialProfileId = null,
                        credentialAlias = null,
                        status = "success",
                        startedAt = 3_000L,
                        finishedAt = 3_100L,
                        durationMs = 100L,
                        triggerType = "cron",
                        errorCode = null,
                        message = null,
                    ),
                ),
            ),
            sessionRepository = FakeSessionRepository(
                runningSessions = listOf(
                    ContinuousSessionRecord(
                        sessionId = "session-a",
                        taskId = "task-a",
                        credentialSetId = "set-a",
                        status = "running",
                        startedAt = 2_000L,
                        finishedAt = null,
                        totalCycles = 4,
                        successCycles = 3,
                        failedCycles = 1,
                        currentCredentialProfileId = "profile-a",
                        currentCredentialAlias = "账号A",
                        nextCredentialProfileId = "profile-b",
                        nextCredentialAlias = "账号B",
                        cursorIndex = 1,
                        lastErrorCode = null,
                    ),
                ),
            ),
        )

        val snapshot = service.loadSnapshot(recentRunLimit = 5)

        assertEquals(2, snapshot.tasks.size)
        assertEquals("success", snapshot.tasks[0].latestRunStatus)
        assertEquals(5_000L, snapshot.tasks[0].nextTriggerAt)
        assertEquals("账号A", snapshot.tasks[0].runningSession?.currentCredentialAlias)
        assertNull(snapshot.tasks[1].latestRunStatus)
        assertNull(snapshot.tasks[1].nextTriggerAt)
        assertNull(snapshot.tasks[1].runningSession)
    }

    @Test
    fun shouldReturnRunningSessionsAndRecentRunsWithTaskNames() = runBlocking {
        val service = AppDashboardService(
            taskRepository = FakeTaskRepository(
                definitions = listOf(
                    TaskDefinitionRecord(
                        taskId = "task-a",
                        name = "任务 A",
                        enabled = true,
                        triggerType = "cron",
                        definitionStatus = "ready",
                        rawJson = "{}",
                        updatedAt = 100L,
                    ),
                ),
            ),
            runRecordRepository = FakeRunRecordRepository(
                recentRuns = listOf(
                    TaskRunRecord(
                        runId = "run-2",
                        sessionId = null,
                        cycleNo = null,
                        taskId = "task-a",
                        credentialSetId = null,
                        credentialProfileId = null,
                        credentialAlias = null,
                        status = "failed",
                        startedAt = 2_000L,
                        finishedAt = 2_200L,
                        durationMs = 200L,
                        triggerType = "manual",
                        errorCode = "RUNNER_STEP_FAILED",
                        message = null,
                    ),
                    TaskRunRecord(
                        runId = "run-1",
                        sessionId = "session-a",
                        cycleNo = 1,
                        taskId = "task-a",
                        credentialSetId = "set-a",
                        credentialProfileId = "profile-a",
                        credentialAlias = "账号A",
                        status = "success",
                        startedAt = 1_000L,
                        finishedAt = 1_100L,
                        durationMs = 100L,
                        triggerType = "continuous",
                        errorCode = null,
                        message = null,
                    ),
                ),
            ),
            sessionRepository = FakeSessionRepository(
                runningSessions = listOf(
                    ContinuousSessionRecord(
                        sessionId = "session-a",
                        taskId = "task-a",
                        credentialSetId = "set-a",
                        status = "running",
                        startedAt = 500L,
                        finishedAt = null,
                        totalCycles = 2,
                        successCycles = 1,
                        failedCycles = 1,
                        currentCredentialProfileId = "profile-a",
                        currentCredentialAlias = "账号A",
                        nextCredentialProfileId = "profile-b",
                        nextCredentialAlias = "账号B",
                        cursorIndex = 1,
                        lastErrorCode = "RUNNER_STEP_FAILED",
                    ),
                ),
            ),
        )

        val snapshot = service.loadSnapshot(recentRunLimit = 1)

        assertEquals(1, snapshot.runningSessions.size)
        assertEquals("任务 A", snapshot.runningSessions[0].taskName)
        assertEquals(1, snapshot.recentRuns.size)
        assertEquals("run-2", snapshot.recentRuns[0].runId)
        assertEquals("任务 A", snapshot.recentRuns[0].taskName)
    }

    private class FakeTaskRepository(
        private val definitions: List<TaskDefinitionRecord>,
        private val scheduleStates: Map<String, TaskScheduleStateRecord> = emptyMap(),
    ) : TaskRepository {
        override suspend fun listTaskDefinitions(): List<TaskDefinitionRecord> = definitions

        override suspend fun getTaskDefinition(taskId: String): TaskDefinitionRecord? = definitions.firstOrNull { it.taskId == taskId }

        override suspend fun upsertTaskDefinition(taskDefinition: TaskDefinitionRecord) = Unit

        override suspend fun updateDefinitionStatus(taskId: String, definitionStatus: String, updatedAt: Long) = Unit

        override suspend fun updateEnabled(taskId: String, enabled: Boolean, updatedAt: Long) = Unit

        override suspend fun getScheduleState(taskId: String): TaskScheduleStateRecord? = scheduleStates[taskId]

        override suspend fun upsertScheduleState(taskScheduleState: TaskScheduleStateRecord) = Unit
    }

    private class FakeRunRecordRepository(
        private val latestByTaskId: Map<String, TaskRunRecord> = emptyMap(),
        private val recentRuns: List<TaskRunRecord> = emptyList(),
    ) : RunRecordRepository {
        override suspend fun upsertTaskRun(taskRun: TaskRunRecord) = Unit

        override suspend fun findLatestTaskRun(taskId: String): TaskRunRecord? = latestByTaskId[taskId]

        override suspend fun listRecentTaskRuns(limit: Int): List<TaskRunRecord> = recentRuns.take(limit)

        override suspend fun listRecentTaskRunsByTaskId(taskId: String, limit: Int): List<TaskRunRecord> =
            recentRuns.filter { it.taskId == taskId }.take(limit)

        override suspend fun findTaskRunsBySession(sessionId: String): List<TaskRunRecord> =
            recentRuns.filter { it.sessionId == sessionId }

        override suspend fun insertStepRuns(stepRuns: List<com.plearn.appcontrol.data.model.StepRunRecord>) = Unit

        override suspend fun findStepRuns(runId: String): List<com.plearn.appcontrol.data.model.StepRunRecord> = emptyList()
    }

    private class FakeSessionRepository(
        private val runningSessions: List<ContinuousSessionRecord> = emptyList(),
    ) : SessionRepository {
        override suspend fun upsertSession(session: ContinuousSessionRecord) = Unit

        override suspend fun findRunningSession(taskId: String): ContinuousSessionRecord? =
            runningSessions.firstOrNull { it.taskId == taskId }

        override suspend fun findRunningSessions(): List<ContinuousSessionRecord> = runningSessions

        override suspend fun updateTerminalState(
            sessionId: String,
            status: String,
            finishedAt: Long?,
            totalCycles: Int,
            successCycles: Int,
            failedCycles: Int,
            lastErrorCode: String?,
        ) = Unit
    }
}
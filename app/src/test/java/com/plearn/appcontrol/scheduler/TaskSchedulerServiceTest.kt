package com.plearn.appcontrol.scheduler

import com.plearn.appcontrol.data.model.ContinuousSessionRecord
import com.plearn.appcontrol.data.model.CredentialSetItemRecord
import com.plearn.appcontrol.data.model.CredentialSetRecord
import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.data.model.TaskScheduleStateRecord
import com.plearn.appcontrol.data.repository.CredentialRepository
import com.plearn.appcontrol.data.repository.RunRecordRepository
import com.plearn.appcontrol.data.repository.SessionRepository
import com.plearn.appcontrol.data.repository.TaskRepository
import com.plearn.appcontrol.dsl.TaskDslParser
import com.plearn.appcontrol.runner.RunTriggerType
import com.plearn.appcontrol.runner.TaskExecutionRecorder
import com.plearn.appcontrol.runner.TaskExecutionResult
import com.plearn.appcontrol.runner.TaskRunStatus
import com.plearn.appcontrol.runner.TaskRunner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskSchedulerServiceTest {
    @Test
    fun shouldExecuteDueCronTaskPersistRunAndAdvanceNextTrigger() = runBlocking {
        val taskRepository = FakeTaskRepository(
            definitions = mutableListOf(
                TaskDefinitionRecord(
                    taskId = "cron-task",
                    name = "Cron Task",
                    enabled = true,
                    triggerType = "cron",
                    definitionStatus = "ready",
                    rawJson = cronTaskJson,
                    updatedAt = 1L,
                ),
            ),
            scheduleStates = mutableMapOf(
                "cron-task" to TaskScheduleStateRecord(
                    taskId = "cron-task",
                    nextTriggerAt = 1_000L,
                    standbyEnabled = true,
                    lastTriggerAt = null,
                    lastScheduleStatus = "scheduled",
                ),
            ),
        )
        val runner = RecordingTaskRunner(status = TaskRunStatus.SUCCESS)
        val recorder = RecordingTaskExecutionRecorder()
        val scheduler = TaskSchedulerService(
            parser = TaskDslParser(),
            taskRepository = taskRepository,
            credentialRepository = FakeCredentialRepository(),
            sessionRepository = FakeSessionRepository(),
            taskRunner = runner,
            executionRecorder = recorder,
            timeSource = FixedSchedulerTimeSource(nowMs = 1_000L),
            cronScheduleCalculator = CronScheduleCalculator(),
            sessionIdFactory = { "session-ignored" },
        )

        val result = scheduler.dispatchDueTasks()

        assertEquals(1, result.executedTaskIds.size)
        assertEquals(listOf("cron-task"), result.executedTaskIds)
        assertEquals(1, runner.invocations.size)
        assertEquals(RunTriggerType.CRON, runner.invocations.single().triggerType)
        assertEquals(1, recorder.recordCount)
        assertEquals(1_000L, taskRepository.scheduleStates.getValue("cron-task").lastTriggerAt)
        assertTrue(taskRepository.scheduleStates.getValue("cron-task").nextTriggerAt!! > 1_000L)
    }

    @Test
    fun shouldExecuteContinuousCyclePersistRunAndKeepSessionRunning() = runBlocking {
        val taskRepository = FakeTaskRepository(
            definitions = mutableListOf(
                TaskDefinitionRecord(
                    taskId = "continuous-task",
                    name = "Continuous Task",
                    enabled = true,
                    triggerType = "continuous",
                    definitionStatus = "ready",
                    rawJson = continuousTaskJson,
                    updatedAt = 1L,
                ),
            ),
            scheduleStates = mutableMapOf(
                "continuous-task" to TaskScheduleStateRecord(
                    taskId = "continuous-task",
                    nextTriggerAt = 1_000L,
                    standbyEnabled = true,
                    lastTriggerAt = null,
                    lastScheduleStatus = "scheduled",
                ),
            ),
        )
        val credentialRepository = FakeCredentialRepository(
            credentialSet = CredentialSetRecord(
                credentialSetId = "smoke-set-a",
                name = "Smoke Set",
                description = null,
                strategy = "round_robin",
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
                items = listOf(CredentialSetItemRecord("smoke-set-a", "profile-a", 0, true)),
            ),
        )
        val sessionRepository = FakeSessionRepository()
        val runner = RecordingTaskRunner(status = TaskRunStatus.SUCCESS)
        val recorder = RecordingTaskExecutionRecorder()
        val scheduler = TaskSchedulerService(
            parser = TaskDslParser(),
            taskRepository = taskRepository,
            credentialRepository = credentialRepository,
            sessionRepository = sessionRepository,
            taskRunner = runner,
            executionRecorder = recorder,
            timeSource = FixedSchedulerTimeSource(nowMs = 1_000L),
            cronScheduleCalculator = CronScheduleCalculator(),
            sessionIdFactory = { "session-001" },
        )

        val result = scheduler.dispatchDueTasks()

        assertEquals(listOf("continuous-task"), result.executedTaskIds)
        assertEquals(1, runner.invocations.size)
        assertEquals(RunTriggerType.CONTINUOUS, runner.invocations.single().triggerType)
        assertEquals(1, recorder.recordCount)
        assertEquals("session-001", recorder.lastSessionId)
        assertEquals(1, recorder.lastCycleNo)
        assertNotNull(sessionRepository.runningSessions["continuous-task"])
        val runningSession = sessionRepository.runningSessions.getValue("continuous-task")
        assertEquals("running", runningSession.status)
        assertEquals(1, runningSession.totalCycles)
        assertEquals(1, runningSession.successCycles)
        assertEquals(6_000L, taskRepository.scheduleStates.getValue("continuous-task").nextTriggerAt)
    }

    @Test
    fun shouldCompleteContinuousSessionWhenMaxCyclesReached() = runBlocking {
        val taskRepository = FakeTaskRepository(
            definitions = mutableListOf(
                TaskDefinitionRecord(
                    taskId = "continuous-task",
                    name = "Continuous Task",
                    enabled = true,
                    triggerType = "continuous",
                    definitionStatus = "ready",
                    rawJson = continuousTaskJson,
                    updatedAt = 1L,
                ),
            ),
            scheduleStates = mutableMapOf(
                "continuous-task" to TaskScheduleStateRecord(
                    taskId = "continuous-task",
                    nextTriggerAt = 6_000L,
                    standbyEnabled = true,
                    lastTriggerAt = 1_000L,
                    lastScheduleStatus = "scheduled",
                ),
            ),
        )
        val credentialRepository = FakeCredentialRepository(
            credentialSet = CredentialSetRecord(
                credentialSetId = "smoke-set-a",
                name = "Smoke Set",
                description = null,
                strategy = "round_robin",
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
                items = listOf(CredentialSetItemRecord("smoke-set-a", "profile-a", 0, true)),
            ),
        )
        val sessionRepository = FakeSessionRepository(
            runningSession = ContinuousSessionRecord(
                sessionId = "session-001",
                taskId = "continuous-task",
                credentialSetId = "smoke-set-a",
                status = "running",
                startedAt = 1_000L,
                finishedAt = null,
                totalCycles = 2,
                successCycles = 2,
                failedCycles = 0,
                currentCredentialProfileId = null,
                currentCredentialAlias = null,
                nextCredentialProfileId = null,
                nextCredentialAlias = null,
                cursorIndex = 0,
                lastErrorCode = null,
            ),
        )
        val runner = RecordingTaskRunner(status = TaskRunStatus.SUCCESS)
        val recorder = RecordingTaskExecutionRecorder()
        val scheduler = TaskSchedulerService(
            parser = TaskDslParser(),
            taskRepository = taskRepository,
            credentialRepository = credentialRepository,
            sessionRepository = sessionRepository,
            taskRunner = runner,
            executionRecorder = recorder,
            timeSource = FixedSchedulerTimeSource(nowMs = 6_000L),
            cronScheduleCalculator = CronScheduleCalculator(),
            sessionIdFactory = { "session-unused" },
        )

        scheduler.dispatchDueTasks()

        assertEquals(1, sessionRepository.terminalUpdates.size)
        val terminalUpdate = sessionRepository.terminalUpdates.single()
        assertEquals("success", terminalUpdate.status)
        assertEquals(3, terminalUpdate.totalCycles)
        assertEquals(null, taskRepository.scheduleStates.getValue("continuous-task").nextTriggerAt)
    }

    @Test
    fun shouldTerminateRunningContinuousSessionWhenCredentialSetBecomesUnavailable() = runBlocking {
        val taskRepository = FakeTaskRepository(
            definitions = mutableListOf(
                TaskDefinitionRecord(
                    taskId = "continuous-task",
                    name = "Continuous Task",
                    enabled = true,
                    triggerType = "continuous",
                    definitionStatus = "ready",
                    rawJson = continuousTaskJson,
                    updatedAt = 1L,
                ),
            ),
            scheduleStates = mutableMapOf(
                "continuous-task" to TaskScheduleStateRecord(
                    taskId = "continuous-task",
                    nextTriggerAt = 6_000L,
                    standbyEnabled = true,
                    lastTriggerAt = 1_000L,
                    lastScheduleStatus = "scheduled",
                ),
            ),
        )
        val sessionRepository = FakeSessionRepository(
            runningSession = ContinuousSessionRecord(
                sessionId = "session-001",
                taskId = "continuous-task",
                credentialSetId = "smoke-set-a",
                status = "running",
                startedAt = 1_000L,
                finishedAt = null,
                totalCycles = 2,
                successCycles = 1,
                failedCycles = 1,
                currentCredentialProfileId = null,
                currentCredentialAlias = null,
                nextCredentialProfileId = null,
                nextCredentialAlias = null,
                cursorIndex = 0,
                lastErrorCode = null,
            ),
        )
        val runner = RecordingTaskRunner(status = TaskRunStatus.SUCCESS)
        val recorder = RecordingTaskExecutionRecorder()
        val scheduler = TaskSchedulerService(
            parser = TaskDslParser(),
            taskRepository = taskRepository,
            credentialRepository = FakeCredentialRepository(),
            sessionRepository = sessionRepository,
            taskRunner = runner,
            executionRecorder = recorder,
            timeSource = FixedSchedulerTimeSource(nowMs = 6_000L),
            cronScheduleCalculator = CronScheduleCalculator(),
            sessionIdFactory = { "session-unused" },
        )

        scheduler.dispatchDueTasks()

        assertTrue(runner.invocations.isEmpty())
        assertEquals(0, recorder.recordCount)
        assertEquals(1, sessionRepository.terminalUpdates.size)
        val terminalUpdate = sessionRepository.terminalUpdates.single()
        assertEquals(TaskRunStatus.BLOCKED, terminalUpdate.status)
        assertEquals(SchedulerFailureCode.SCHEDULER_CREDENTIAL_SET_UNAVAILABLE, terminalUpdate.lastErrorCode)
        assertTrue(sessionRepository.runningSessions.isEmpty())
        assertEquals(ScheduleStatus.BLOCKED, taskRepository.scheduleStates.getValue("continuous-task").lastScheduleStatus)
        assertEquals(null, taskRepository.scheduleStates.getValue("continuous-task").nextTriggerAt)
    }

    private class RecordingTaskRunner(
        private val status: String,
    ) : TaskRunner {
        val invocations = mutableListOf<Invocation>()

        override suspend fun run(task: com.plearn.appcontrol.dsl.TaskDefinition, triggerType: String): TaskExecutionResult {
            invocations += Invocation(task.taskId, triggerType)
            return TaskExecutionResult(
                taskRun = TaskRunRecord(
                    runId = "run-${invocations.size}",
                    sessionId = null,
                    cycleNo = null,
                    taskId = task.taskId,
                    credentialSetId = null,
                    credentialProfileId = null,
                    credentialAlias = null,
                    status = status,
                    startedAt = 1_000L,
                    finishedAt = 1_200L,
                    durationMs = 200L,
                    triggerType = triggerType,
                    errorCode = null,
                    message = null,
                ),
                stepRuns = listOf(
                    StepRunRecord(
                        runId = "run-${invocations.size}",
                        stepId = "step-start",
                        status = status,
                        startedAt = 1_000L,
                        finishedAt = 1_100L,
                        durationMs = 100L,
                        errorCode = null,
                        message = null,
                        artifactsJson = "{}",
                    ),
                ),
                taskAttemptCount = 1,
            )
        }
    }

    private data class Invocation(val taskId: String, val triggerType: String)

    private class RecordingTaskExecutionRecorder : TaskExecutionRecorder {
        var recordCount: Int = 0
        var lastSessionId: String? = null
        var lastCycleNo: Int? = null

        override suspend fun record(
            result: TaskExecutionResult,
            sessionId: String?,
            cycleNo: Int?,
        ): TaskExecutionResult {
            recordCount += 1
            lastSessionId = sessionId
            lastCycleNo = cycleNo
            return result.copy(
                taskRun = result.taskRun.copy(
                    sessionId = sessionId,
                    cycleNo = cycleNo,
                ),
            )
        }
    }

    private class FakeTaskRepository(
        val definitions: MutableList<TaskDefinitionRecord>,
        val scheduleStates: MutableMap<String, TaskScheduleStateRecord>,
    ) : TaskRepository {
        override suspend fun listTaskDefinitions(): List<TaskDefinitionRecord> = definitions.toList()

        override suspend fun getTaskDefinition(taskId: String): TaskDefinitionRecord? = definitions.firstOrNull { it.taskId == taskId }

        override suspend fun upsertTaskDefinition(taskDefinition: TaskDefinitionRecord) {
            definitions.removeAll { it.taskId == taskDefinition.taskId }
            definitions += taskDefinition
        }

        override suspend fun updateDefinitionStatus(taskId: String, definitionStatus: String, updatedAt: Long) = Unit

        override suspend fun updateEnabled(taskId: String, enabled: Boolean, updatedAt: Long) = Unit

        override suspend fun getScheduleState(taskId: String): TaskScheduleStateRecord? = scheduleStates[taskId]

        override suspend fun upsertScheduleState(taskScheduleState: TaskScheduleStateRecord) {
            scheduleStates[taskScheduleState.taskId] = taskScheduleState
        }
    }

    private class FakeCredentialRepository(
        private val credentialSet: CredentialSetRecord? = null,
    ) : CredentialRepository {
        override suspend fun upsertCredentialProfile(profile: com.plearn.appcontrol.data.model.CredentialProfileRecord) = Unit

        override suspend fun upsertCredentialSecret(secret: com.plearn.appcontrol.data.model.CredentialSecretRecord) = Unit

        override suspend fun getEnabledProfiles(): List<com.plearn.appcontrol.data.model.CredentialProfileRecord> = emptyList()

        override suspend fun replaceCredentialSet(credentialSet: CredentialSetRecord) = Unit

        override suspend fun getCredentialSet(credentialSetId: String): CredentialSetRecord? =
            if (credentialSet?.credentialSetId == credentialSetId) credentialSet else null
    }

    private class FakeSessionRepository(
        runningSession: ContinuousSessionRecord? = null,
    ) : SessionRepository {
        val runningSessions = mutableMapOf<String, ContinuousSessionRecord>()
        val terminalUpdates = mutableListOf<TerminalUpdate>()

        init {
            if (runningSession != null) {
                runningSessions[runningSession.taskId] = runningSession
            }
        }

        override suspend fun upsertSession(session: ContinuousSessionRecord) {
            runningSessions[session.taskId] = session
        }

        override suspend fun findRunningSession(taskId: String): ContinuousSessionRecord? = runningSessions[taskId]

        override suspend fun updateTerminalState(
            sessionId: String,
            status: String,
            finishedAt: Long?,
            totalCycles: Int,
            successCycles: Int,
            failedCycles: Int,
            lastErrorCode: String?,
        ) {
            terminalUpdates += TerminalUpdate(sessionId, status, finishedAt, totalCycles, successCycles, failedCycles, lastErrorCode)
            runningSessions.entries.removeIf { it.value.sessionId == sessionId }
        }
    }

    private data class TerminalUpdate(
        val sessionId: String,
        val status: String,
        val finishedAt: Long?,
        val totalCycles: Int,
        val successCycles: Int,
        val failedCycles: Int,
        val lastErrorCode: String?,
    )

    private class FixedSchedulerTimeSource(
        private val nowMs: Long,
    ) : SchedulerTimeSource {
        override fun nowMs(): Long = nowMs
    }

    private companion object {
        val cronTaskJson = """
            {
              "schemaVersion": "1.0",
              "taskId": "cron-task",
              "name": "Cron Task",
              "enabled": true,
              "targetApp": {
                "packageName": "com.example.target"
              },
              "trigger": {
                "type": "cron",
                "expression": "*/30 * * * *",
                "timezone": "Asia/Shanghai"
              },
              "executionPolicy": {
                "taskTimeoutMs": 300000,
                "maxRetries": 0,
                "retryBackoffMs": 1000,
                "conflictPolicy": "skip",
                "onMissedSchedule": "skip"
              },
              "steps": [
                {
                  "id": "step-start",
                  "type": "start_app",
                  "timeoutMs": 15000,
                  "params": {
                    "packageName": "com.example.target"
                  }
                }
              ]
            }
        """.trimIndent()

        val continuousTaskJson = """
            {
              "schemaVersion": "1.0",
              "taskId": "continuous-task",
              "name": "Continuous Task",
              "enabled": true,
              "targetApp": {
                "packageName": "com.example.target"
              },
              "trigger": {
                "type": "continuous",
                "cooldownMs": 5000,
                "maxCycles": 3,
                "maxDurationMs": 600000
              },
              "accountRotation": {
                "credentialSetId": "smoke-set-a",
                "strategy": "round_robin",
                "persistCursor": true,
                "onCycleFailure": "continue_next"
              },
              "executionPolicy": {
                "taskTimeoutMs": 300000,
                "maxRetries": 0,
                "retryBackoffMs": 1000,
                "conflictPolicy": "skip",
                "onMissedSchedule": "skip"
              },
              "steps": [
                {
                  "id": "step-start",
                  "type": "start_app",
                  "timeoutMs": 15000,
                  "params": {
                    "packageName": "com.example.target"
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
package com.plearn.appcontrol.scheduler

import com.plearn.appcontrol.data.model.ContinuousSessionRecord
import com.plearn.appcontrol.data.model.CredentialProfileRecord
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
    fun shouldSkipMissedCronDuringRecoveryAndAdvanceNextTrigger() = runBlocking {
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
                    lastScheduleStatus = ScheduleStatus.SCHEDULED,
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
            timeSource = FixedSchedulerTimeSource(nowMs = 61_000L),
            cronScheduleCalculator = CronScheduleCalculator(),
            sessionIdFactory = { "session-ignored" },
        )

        val result = scheduler.dispatchDueTasks(mode = SchedulerDispatchMode.RECOVERY)

        assertTrue(result.executedTaskIds.isEmpty())
        assertTrue(runner.invocations.isEmpty())
        assertEquals(0, recorder.recordCount)
        assertEquals(ScheduleStatus.MISSED_SKIPPED, taskRepository.scheduleStates.getValue("cron-task").lastScheduleStatus)
        assertEquals(1_000L, taskRepository.scheduleStates.getValue("cron-task").lastTriggerAt)
        assertTrue(taskRepository.scheduleStates.getValue("cron-task").nextTriggerAt!! > 61_000L)
    }

    @Test
    fun shouldSkipCronWhenExecutionLockIsOccupiedAndConflictPolicyIsSkip() = runBlocking {
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
                    lastScheduleStatus = ScheduleStatus.SCHEDULED,
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
            executionLock = FakeTaskExecutionLock(lockedPackages = mutableSetOf("com.example.target")),
            sessionIdFactory = { "session-ignored" },
        )

        val result = scheduler.dispatchDueTasks()

        assertTrue(result.executedTaskIds.isEmpty())
        assertTrue(runner.invocations.isEmpty())
        assertEquals(0, recorder.recordCount)
        assertEquals(ScheduleStatus.CONFLICT_SKIPPED, taskRepository.scheduleStates.getValue("cron-task").lastScheduleStatus)
        assertTrue(taskRepository.scheduleStates.getValue("cron-task").nextTriggerAt!! > 1_000L)
    }

    @Test
    fun shouldDelayCronWhenExecutionLockIsOccupiedAndConflictPolicyIsRunAfterCurrent() = runBlocking {
        val taskRepository = FakeTaskRepository(
            definitions = mutableListOf(
                TaskDefinitionRecord(
                    taskId = "cron-task-delayed",
                    name = "Cron Task Delayed",
                    enabled = true,
                    triggerType = "cron",
                    definitionStatus = "ready",
                    rawJson = cronTaskJsonRunAfterCurrent,
                    updatedAt = 1L,
                ),
            ),
            scheduleStates = mutableMapOf(
                "cron-task-delayed" to TaskScheduleStateRecord(
                    taskId = "cron-task-delayed",
                    nextTriggerAt = 1_000L,
                    standbyEnabled = true,
                    lastTriggerAt = null,
                    lastScheduleStatus = ScheduleStatus.SCHEDULED,
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
            executionLock = FakeTaskExecutionLock(lockedPackages = mutableSetOf("com.example.target")),
            sessionIdFactory = { "session-ignored" },
        )

        val result = scheduler.dispatchDueTasks()

        assertTrue(result.executedTaskIds.isEmpty())
        assertTrue(runner.invocations.isEmpty())
        assertEquals(0, recorder.recordCount)
        assertEquals(ScheduleStatus.CONFLICT_DELAYED, taskRepository.scheduleStates.getValue("cron-task-delayed").lastScheduleStatus)
        assertEquals(1_000L, taskRepository.scheduleStates.getValue("cron-task-delayed").nextTriggerAt)
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
    fun shouldPersistContinuousCredentialCursorAndAnnotateTaskRun() = runBlocking {
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
                    lastScheduleStatus = ScheduleStatus.SCHEDULED,
                ),
            ),
        )
        val credentialRepository = FakeCredentialRepository(
            credentialSet = multiProfileCredentialSet,
            enabledProfiles = multiProfileEnabledProfiles,
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

        scheduler.dispatchDueTasks()

        assertEquals("smoke-set-a", recorder.lastResult?.taskRun?.credentialSetId)
        assertEquals("profile-a", recorder.lastResult?.taskRun?.credentialProfileId)
        assertEquals("Alias A", recorder.lastResult?.taskRun?.credentialAlias)
        val runningSession = sessionRepository.runningSessions.getValue("continuous-task")
        assertEquals("profile-a", runningSession.currentCredentialProfileId)
        assertEquals("Alias A", runningSession.currentCredentialAlias)
        assertEquals("profile-b", runningSession.nextCredentialProfileId)
        assertEquals("Alias B", runningSession.nextCredentialAlias)
        assertEquals(1, runningSession.cursorIndex)
    }

    @Test
    fun shouldResumeContinuousCursorFromRecoveredRunningSession() = runBlocking {
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
                    lastScheduleStatus = ScheduleStatus.SCHEDULED,
                ),
            ),
        )
        val credentialRepository = FakeCredentialRepository(
            credentialSet = multiProfileCredentialSet,
            enabledProfiles = multiProfileEnabledProfiles,
        )
        val sessionRepository = FakeSessionRepository(
            runningSession = ContinuousSessionRecord(
                sessionId = "session-001",
                taskId = "continuous-task",
                credentialSetId = "smoke-set-a",
                status = "running",
                startedAt = 1_000L,
                finishedAt = null,
                totalCycles = 1,
                successCycles = 1,
                failedCycles = 0,
                currentCredentialProfileId = "profile-a",
                currentCredentialAlias = "Alias A",
                nextCredentialProfileId = "profile-b",
                nextCredentialAlias = "Alias B",
                cursorIndex = 1,
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

        assertEquals("profile-b", recorder.lastResult?.taskRun?.credentialProfileId)
        assertEquals("Alias B", recorder.lastResult?.taskRun?.credentialAlias)
        val runningSession = sessionRepository.runningSessions.getValue("continuous-task")
        assertEquals("profile-b", runningSession.currentCredentialProfileId)
        assertEquals("Alias B", runningSession.currentCredentialAlias)
        assertEquals("profile-a", runningSession.nextCredentialProfileId)
        assertEquals("Alias A", runningSession.nextCredentialAlias)
        assertEquals(0, runningSession.cursorIndex)
    }

    @Test
    fun shouldStopContinuousSessionWhenCycleFailsAndPolicyIsStopSession() = runBlocking {
        val taskRepository = FakeTaskRepository(
            definitions = mutableListOf(
                TaskDefinitionRecord(
                    taskId = "continuous-task",
                    name = "Continuous Task",
                    enabled = true,
                    triggerType = "continuous",
                    definitionStatus = "ready",
                    rawJson = continuousTaskJsonStopSession,
                    updatedAt = 1L,
                ),
            ),
            scheduleStates = mutableMapOf(
                "continuous-task" to TaskScheduleStateRecord(
                    taskId = "continuous-task",
                    nextTriggerAt = 1_000L,
                    standbyEnabled = true,
                    lastTriggerAt = null,
                    lastScheduleStatus = ScheduleStatus.SCHEDULED,
                ),
            ),
        )
        val credentialRepository = FakeCredentialRepository(
            credentialSet = multiProfileCredentialSet,
            enabledProfiles = multiProfileEnabledProfiles,
        )
        val sessionRepository = FakeSessionRepository()
        val runner = RecordingTaskRunner(status = TaskRunStatus.FAILED)
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

        scheduler.dispatchDueTasks()

        assertEquals(1, sessionRepository.terminalUpdates.size)
        assertEquals(TaskRunStatus.FAILED, sessionRepository.terminalUpdates.single().status)
        assertTrue(sessionRepository.runningSessions.isEmpty())
        assertEquals(null, taskRepository.scheduleStates.getValue("continuous-task").nextTriggerAt)
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

    @Test
    fun shouldRunOnlyOneContinuousTaskPerPackagePerDispatch() = runBlocking {
        val taskRepository = FakeTaskRepository(
            definitions = mutableListOf(
                TaskDefinitionRecord(
                    taskId = "continuous-task-a",
                    name = "Continuous Task A",
                    enabled = true,
                    triggerType = "continuous",
                    definitionStatus = "ready",
                    rawJson = continuousTaskJsonA,
                    updatedAt = 1L,
                ),
                TaskDefinitionRecord(
                    taskId = "continuous-task-b",
                    name = "Continuous Task B",
                    enabled = true,
                    triggerType = "continuous",
                    definitionStatus = "ready",
                    rawJson = continuousTaskJsonB,
                    updatedAt = 1L,
                ),
            ),
            scheduleStates = mutableMapOf(
                "continuous-task-a" to TaskScheduleStateRecord(
                    taskId = "continuous-task-a",
                    nextTriggerAt = 1_000L,
                    standbyEnabled = true,
                    lastTriggerAt = null,
                    lastScheduleStatus = ScheduleStatus.SCHEDULED,
                ),
                "continuous-task-b" to TaskScheduleStateRecord(
                    taskId = "continuous-task-b",
                    nextTriggerAt = 1_000L,
                    standbyEnabled = true,
                    lastTriggerAt = null,
                    lastScheduleStatus = ScheduleStatus.SCHEDULED,
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
            sessionIdFactory = { "session-generated" },
        )

        val result = scheduler.dispatchDueTasks()

        assertEquals(listOf("continuous-task-a"), result.executedTaskIds)
        assertEquals(1, runner.invocations.size)
        assertEquals("continuous-task-a", runner.invocations.single().taskId)
        assertEquals(1, recorder.recordCount)
        assertEquals(6_000L, taskRepository.scheduleStates.getValue("continuous-task-a").nextTriggerAt)
        assertEquals(1_000L, taskRepository.scheduleStates.getValue("continuous-task-b").nextTriggerAt)
        assertEquals(null, taskRepository.scheduleStates.getValue("continuous-task-b").lastTriggerAt)
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
        var lastResult: TaskExecutionResult? = null

        override suspend fun record(
            result: TaskExecutionResult,
            sessionId: String?,
            cycleNo: Int?,
        ): TaskExecutionResult {
            val persistedResult = result.copy(
                taskRun = result.taskRun.copy(
                    sessionId = sessionId,
                    cycleNo = cycleNo,
                ),
            )
            recordCount += 1
            lastSessionId = sessionId
            lastCycleNo = cycleNo
            lastResult = persistedResult
            return persistedResult
        }
    }

    private class FakeTaskExecutionLock(
        private val lockedPackages: MutableSet<String> = mutableSetOf(),
    ) : TaskExecutionLock {
        override suspend fun tryAcquire(packageName: String): Boolean = lockedPackages.add(packageName)

        override suspend fun release(packageName: String) {
            lockedPackages.remove(packageName)
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
        private val enabledProfiles: List<CredentialProfileRecord> = emptyList(),
    ) : CredentialRepository {
        override suspend fun upsertCredentialProfile(profile: com.plearn.appcontrol.data.model.CredentialProfileRecord) = Unit

        override suspend fun upsertCredentialSecret(secret: com.plearn.appcontrol.data.model.CredentialSecretRecord) = Unit

        override suspend fun getEnabledProfiles(): List<com.plearn.appcontrol.data.model.CredentialProfileRecord> =
            if (enabledProfiles.isNotEmpty()) {
                enabledProfiles
            } else {
                credentialSet?.items
                    ?.filter { it.enabled }
                    ?.map { item ->
                        CredentialProfileRecord(
                            profileId = item.profileId,
                            alias = item.profileId,
                            tagsJson = "[]",
                            enabled = true,
                            createdAt = 1L,
                            updatedAt = 1L,
                        )
                    }
                    ?: emptyList()
            }

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

                val cronTaskJsonRunAfterCurrent = """
                        {
                            "schemaVersion": "1.0",
                            "taskId": "cron-task-delayed",
                            "name": "Cron Task Delayed",
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
                                "conflictPolicy": "run_after_current",
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

        val continuousTaskJsonStopSession = continuousTaskJson.replace("\"continue_next\"", "\"stop_session\"")

        val multiProfileEnabledProfiles = listOf(
            CredentialProfileRecord(
                profileId = "profile-a",
                alias = "Alias A",
                tagsJson = "[]",
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
            CredentialProfileRecord(
                profileId = "profile-b",
                alias = "Alias B",
                tagsJson = "[]",
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )

        val multiProfileCredentialSet = CredentialSetRecord(
            credentialSetId = "smoke-set-a",
            name = "Smoke Set",
            description = null,
            strategy = "round_robin",
            enabled = true,
            createdAt = 1L,
            updatedAt = 1L,
            items = listOf(
                CredentialSetItemRecord("smoke-set-a", "profile-a", 0, true),
                CredentialSetItemRecord("smoke-set-a", "profile-b", 1, true),
            ),
        )

        val continuousTaskJsonA = continuousTaskJson
            .replace("\"continuous-task\"", "\"continuous-task-a\"")
            .replace("\"Continuous Task\"", "\"Continuous Task A\"")

        val continuousTaskJsonB = continuousTaskJson
            .replace("\"continuous-task\"", "\"continuous-task-b\"")
            .replace("\"Continuous Task\"", "\"Continuous Task B\"")
    }
}
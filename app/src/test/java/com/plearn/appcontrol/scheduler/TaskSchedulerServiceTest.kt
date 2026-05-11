package com.plearn.appcontrol.scheduler

import com.plearn.appcontrol.capability.CapabilityFacade
import com.plearn.appcontrol.capability.CapabilityResult
import com.plearn.appcontrol.capability.ElementSelector
import com.plearn.appcontrol.capability.InputTextRequest
import com.plearn.appcontrol.capability.InputTextSummary
import com.plearn.appcontrol.capability.ScreenshotCapture
import com.plearn.appcontrol.capability.ScreenshotCaptureRequest
import com.plearn.appcontrol.capability.SwipeRequest
import com.plearn.appcontrol.capability.TapTarget
import com.plearn.appcontrol.capability.WaitElementState
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
import com.plearn.appcontrol.runner.DiagnosticsArtifactCaptureGate
import com.plearn.appcontrol.runner.RunTriggerType
import com.plearn.appcontrol.runner.TaskExecutionRecorder
import com.plearn.appcontrol.runner.TaskExecutionResult
import com.plearn.appcontrol.runner.TaskRunStatus
import com.plearn.appcontrol.runner.TaskRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    fun shouldRecordBlockedCronRunWhenExecutionThrowsException() = runBlocking {
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
        val runner = RecordingTaskRunner(
            status = TaskRunStatus.SUCCESS,
            failureTaskIds = setOf("cron-task"),
        )
        val recorder = RecordingTaskExecutionRecorder()
        val capabilityFacade = RecordingCapabilityFacade()
        val diagnosticsArtifactCaptureGate = RecordingDiagnosticsArtifactCaptureGate(allowed = true)
        val scheduler = TaskSchedulerService(
            parser = TaskDslParser(),
            taskRepository = taskRepository,
            credentialRepository = FakeCredentialRepository(),
            sessionRepository = FakeSessionRepository(),
            taskRunner = runner,
            executionRecorder = recorder,
            capabilityFacade = capabilityFacade,
            diagnosticsArtifactCaptureGate = diagnosticsArtifactCaptureGate,
            timeSource = FixedSchedulerTimeSource(nowMs = 1_000L),
            cronScheduleCalculator = CronScheduleCalculator(),
            sessionIdFactory = { "session-ignored" },
            runIdFactory = { "cron-run-exception" },
        )

        val result = scheduler.dispatchDueTasks()

        assertTrue(result.executedTaskIds.isEmpty())
        assertEquals(1, runner.invocations.size)
        assertEquals(1, recorder.recordCount)
        assertEquals(TaskRunStatus.BLOCKED, recorder.lastResult?.taskRun?.status)
        assertEquals(SchedulerFailureCode.SCHEDULER_EXECUTION_EXCEPTION, recorder.lastResult?.taskRun?.errorCode)
        assertEquals("screenshot", recorder.lastResult?.taskRun?.artifactType())
        assertEquals(
            null,
            recorder.lastResult?.taskRun?.artifactReason(),
        )
        assertEquals("cron-task/cron-run-exception/run.png", recorder.lastResult?.taskRun?.artifactRelativePath())
        assertEquals(
            listOf(
                ScreenshotCaptureRequest(
                    taskId = "cron-task",
                    runId = "cron-run-exception",
                    stepId = null,
                    attempt = null,
                    taskAttempt = null,
                ),
            ),
            capabilityFacade.screenshotRequests,
        )
        assertEquals(listOf("cron-task" to "cron-run-exception"), diagnosticsArtifactCaptureGate.invocations)
        assertEquals(ScheduleStatus.BLOCKED, taskRepository.scheduleStates.getValue("cron-task").lastScheduleStatus)
        assertEquals(1_000L, taskRepository.scheduleStates.getValue("cron-task").lastTriggerAt)
        assertTrue(taskRepository.scheduleStates.getValue("cron-task").nextTriggerAt!! > 1_000L)
    }

    @Test
    fun shouldSkipCronExecutionExceptionScreenshotWhenArtifactGateRejectsCapture() = runBlocking {
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
        val runner = RecordingTaskRunner(
            status = TaskRunStatus.SUCCESS,
            failureTaskIds = setOf("cron-task"),
        )
        val recorder = RecordingTaskExecutionRecorder()
        val capabilityFacade = RecordingCapabilityFacade()
        val diagnosticsArtifactCaptureGate = RecordingDiagnosticsArtifactCaptureGate(allowed = false)
        val scheduler = TaskSchedulerService(
            parser = TaskDslParser(),
            taskRepository = taskRepository,
            credentialRepository = FakeCredentialRepository(),
            sessionRepository = FakeSessionRepository(),
            taskRunner = runner,
            executionRecorder = recorder,
            capabilityFacade = capabilityFacade,
            diagnosticsArtifactCaptureGate = diagnosticsArtifactCaptureGate,
            timeSource = FixedSchedulerTimeSource(nowMs = 1_000L),
            cronScheduleCalculator = CronScheduleCalculator(),
            sessionIdFactory = { "session-ignored" },
            runIdFactory = { "cron-run-gate-reject" },
        )

        val result = scheduler.dispatchDueTasks()

        assertTrue(result.executedTaskIds.isEmpty())
        assertEquals(1, recorder.recordCount)
        assertEquals(TaskRunStatus.BLOCKED, recorder.lastResult?.taskRun?.status)
        assertEquals("screenshot_skipped", recorder.lastResult?.taskRun?.artifactType())
        assertEquals("DIAG_ARTIFACT_STORAGE_LIMIT_REACHED", recorder.lastResult?.taskRun?.artifactReason())
        assertEquals(null, recorder.lastResult?.taskRun?.artifactRelativePath())
        assertTrue(capabilityFacade.screenshotRequests.isEmpty())
        assertEquals(listOf("cron-task" to "cron-run-gate-reject"), diagnosticsArtifactCaptureGate.invocations)
    }

    @Test
    fun shouldMarkCronExecutionExceptionScreenshotUnavailableWhenCaptureFails() = runBlocking {
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
        val runner = RecordingTaskRunner(
            status = TaskRunStatus.SUCCESS,
            failureTaskIds = setOf("cron-task"),
        )
        val recorder = RecordingTaskExecutionRecorder()
        val capabilityFacade = RecordingCapabilityFacade(
            screenshotResult = CapabilityResult.Failure(
                errorCode = "STEP_EXECUTION_FAILED",
                message = "capture failed",
            ),
        )
        val diagnosticsArtifactCaptureGate = RecordingDiagnosticsArtifactCaptureGate(allowed = true)
        val scheduler = TaskSchedulerService(
            parser = TaskDslParser(),
            taskRepository = taskRepository,
            credentialRepository = FakeCredentialRepository(),
            sessionRepository = FakeSessionRepository(),
            taskRunner = runner,
            executionRecorder = recorder,
            capabilityFacade = capabilityFacade,
            diagnosticsArtifactCaptureGate = diagnosticsArtifactCaptureGate,
            timeSource = FixedSchedulerTimeSource(nowMs = 1_000L),
            cronScheduleCalculator = CronScheduleCalculator(),
            sessionIdFactory = { "session-ignored" },
            runIdFactory = { "cron-run-capture-failed" },
        )

        val result = scheduler.dispatchDueTasks()

        assertTrue(result.executedTaskIds.isEmpty())
        assertEquals(1, recorder.recordCount)
        assertEquals(TaskRunStatus.BLOCKED, recorder.lastResult?.taskRun?.status)
        assertEquals("screenshot_unavailable", recorder.lastResult?.taskRun?.artifactType())
        assertEquals("DIAG_SCREENSHOT_CAPTURE_FAILED", recorder.lastResult?.taskRun?.artifactReason())
        assertEquals(null, recorder.lastResult?.taskRun?.artifactRelativePath())
        assertEquals(
            listOf(
                ScreenshotCaptureRequest(
                    taskId = "cron-task",
                    runId = "cron-run-capture-failed",
                    stepId = null,
                    attempt = null,
                    taskAttempt = null,
                ),
            ),
            capabilityFacade.screenshotRequests,
        )
        assertEquals(listOf("cron-task" to "cron-run-capture-failed"), diagnosticsArtifactCaptureGate.invocations)
    }

    @Test
    fun shouldNotRecordSyntheticCronRunWhenRecorderThrows() = runBlocking {
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
        val recorder = RecordingTaskExecutionRecorder(failureOnRecordCount = 1)
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

        try {
            scheduler.dispatchDueTasks()
            fail("Expected recorder failure to propagate")
        } catch (error: IllegalStateException) {
            assertEquals("recorder failure", error.message)
        }

        assertEquals(1, runner.invocations.size)
        assertEquals(1, recorder.recordCount)
        assertEquals(1, recorder.recordedResults.size)
        assertEquals(TaskRunStatus.SUCCESS, recorder.recordedResults.single().taskRun.status)
        assertEquals(ScheduleStatus.SCHEDULED, taskRepository.scheduleStates.getValue("cron-task").lastScheduleStatus)
        assertEquals(null, taskRepository.scheduleStates.getValue("cron-task").lastTriggerAt)
        assertEquals(1_000L, taskRepository.scheduleStates.getValue("cron-task").nextTriggerAt)
    }

    @Test
    fun shouldNotUpdateCronScheduleStateWhenSyntheticRecordingThrows() = runBlocking {
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
        val runner = RecordingTaskRunner(
            status = TaskRunStatus.SUCCESS,
            failureTaskIds = setOf("cron-task"),
        )
        val recorder = RecordingTaskExecutionRecorder(failureOnRecordCount = 1)
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

        try {
            scheduler.dispatchDueTasks()
            fail("Expected recorder failure to propagate")
        } catch (error: IllegalStateException) {
            assertEquals("recorder failure", error.message)
        }

        assertEquals(1, runner.invocations.size)
        assertEquals(1, recorder.recordCount)
        assertEquals(1, recorder.recordedResults.size)
        assertEquals(TaskRunStatus.BLOCKED, recorder.recordedResults.single().taskRun.status)
        assertEquals(SchedulerFailureCode.SCHEDULER_EXECUTION_EXCEPTION, recorder.recordedResults.single().taskRun.errorCode)
        assertEquals(ScheduleStatus.SCHEDULED, taskRepository.scheduleStates.getValue("cron-task").lastScheduleStatus)
        assertEquals(null, taskRepository.scheduleStates.getValue("cron-task").lastTriggerAt)
        assertEquals(1_000L, taskRepository.scheduleStates.getValue("cron-task").nextTriggerAt)
    }

    @Test
    fun shouldPropagateCronCancellationWithoutSyntheticRun() = runBlocking {
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
        val runner = RecordingTaskRunner(
            status = TaskRunStatus.SUCCESS,
            cancellationTaskIds = setOf("cron-task"),
        )
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

        try {
            scheduler.dispatchDueTasks()
            fail("Expected cancellation to propagate")
        } catch (error: CancellationException) {
            assertEquals("runner cancellation for cron-task", error.message)
        }

        assertEquals(1, runner.invocations.size)
        assertEquals(0, recorder.recordCount)
        assertTrue(recorder.recordedResults.isEmpty())
        assertEquals(ScheduleStatus.SCHEDULED, taskRepository.scheduleStates.getValue("cron-task").lastScheduleStatus)
        assertEquals(null, taskRepository.scheduleStates.getValue("cron-task").lastTriggerAt)
        assertEquals(1_000L, taskRepository.scheduleStates.getValue("cron-task").nextTriggerAt)
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
    fun shouldBlockContinuousTaskWhenCredentialSetIsDisabled() = runBlocking {
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
            credentialSet = CredentialSetRecord(
                credentialSetId = "smoke-set-a",
                name = "Smoke Set",
                description = null,
                strategy = "round_robin",
                enabled = false,
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

        assertTrue(result.executedTaskIds.isEmpty())
        assertTrue(runner.invocations.isEmpty())
        assertEquals(1, recorder.recordCount)
        assertEquals(TaskRunStatus.BLOCKED, recorder.lastResult?.taskRun?.status)
        assertEquals(SchedulerFailureCode.SCHEDULER_CREDENTIAL_SET_UNAVAILABLE, recorder.lastResult?.taskRun?.errorCode)
        assertEquals(1, recorder.lastCycleNo)
        assertEquals(
            "DIAG_SCREENSHOT_NOT_CAPTURED_BEFORE_FIRST_ACTION_BLOCK",
            recorder.lastResult?.taskRun?.artifactReason(),
        )
        assertEquals(1, sessionRepository.terminalUpdates.size)
        assertEquals(TaskRunStatus.BLOCKED, sessionRepository.terminalUpdates.single().status)
        assertTrue(sessionRepository.runningSessions.isEmpty())
        assertEquals(ScheduleStatus.BLOCKED, taskRepository.scheduleStates.getValue("continuous-task").lastScheduleStatus)
        assertEquals(null, taskRepository.scheduleStates.getValue("continuous-task").nextTriggerAt)
    }

    @Test
    fun shouldContinueDispatchingSamePackageContinuousTaskWhenEarlierTaskBlocks() = runBlocking {
        val blockedTaskJson = continuousTaskJsonA.replace("\"smoke-set-a\"", "\"missing-set\"")
        val taskRepository = FakeTaskRepository(
            definitions = mutableListOf(
                TaskDefinitionRecord(
                    taskId = "continuous-task-a",
                    name = "Continuous Task A",
                    enabled = true,
                    triggerType = "continuous",
                    definitionStatus = "ready",
                    rawJson = blockedTaskJson,
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

        assertEquals(listOf("continuous-task-b"), result.executedTaskIds)
        assertEquals(1, runner.invocations.size)
        assertEquals("continuous-task-b", runner.invocations.single().taskId)
        assertEquals(ScheduleStatus.BLOCKED, taskRepository.scheduleStates.getValue("continuous-task-a").lastScheduleStatus)
        assertEquals(6_000L, taskRepository.scheduleStates.getValue("continuous-task-b").nextTriggerAt)
    }

    @Test
    fun shouldContinueDispatchAfterContinuousExecutionExceptionAndTerminateFailedSession() = runBlocking {
        val otherPackageTaskJson = continuousTaskJsonB.replace("com.example.target", "com.example.other")
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
                    rawJson = otherPackageTaskJson,
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
        val runner = RecordingTaskRunner(
            status = TaskRunStatus.SUCCESS,
            failureTaskIds = setOf("continuous-task-a"),
        )
        val recorder = RecordingTaskExecutionRecorder()
        val capabilityFacade = RecordingCapabilityFacade()
        val diagnosticsArtifactCaptureGate = RecordingDiagnosticsArtifactCaptureGate(allowed = true)
        val scheduler = TaskSchedulerService(
            parser = TaskDslParser(),
            taskRepository = taskRepository,
            credentialRepository = credentialRepository,
            sessionRepository = sessionRepository,
            taskRunner = runner,
            executionRecorder = recorder,
            capabilityFacade = capabilityFacade,
            diagnosticsArtifactCaptureGate = diagnosticsArtifactCaptureGate,
            timeSource = FixedSchedulerTimeSource(nowMs = 1_000L),
            cronScheduleCalculator = CronScheduleCalculator(),
            sessionIdFactory = { "session-generated" },
            runIdFactory = { "continuous-run-exception" },
        )

        val result = scheduler.dispatchDueTasks()

        assertEquals(listOf("continuous-task-b"), result.executedTaskIds)
        assertEquals(2, runner.invocations.size)
        assertEquals(2, recorder.recordCount)
        val blockedResult = recorder.recordedResults.first { it.taskRun.taskId == "continuous-task-a" }
        assertEquals(TaskRunStatus.BLOCKED, blockedResult.taskRun.status)
        assertEquals(SchedulerFailureCode.SCHEDULER_EXECUTION_EXCEPTION, blockedResult.taskRun.errorCode)
        assertEquals("screenshot", blockedResult.taskRun.artifactType())
        assertEquals(
            null,
            blockedResult.taskRun.artifactReason(),
        )
        assertEquals("continuous-task-a/continuous-run-exception/run.png", blockedResult.taskRun.artifactRelativePath())
        assertEquals(
            listOf(
                ScreenshotCaptureRequest(
                    taskId = "continuous-task-a",
                    runId = "continuous-run-exception",
                    stepId = null,
                    attempt = null,
                    taskAttempt = null,
                ),
            ),
            capabilityFacade.screenshotRequests,
        )
        assertEquals(listOf("continuous-task-a" to "continuous-run-exception"), diagnosticsArtifactCaptureGate.invocations)
        assertEquals(ScheduleStatus.BLOCKED, taskRepository.scheduleStates.getValue("continuous-task-a").lastScheduleStatus)
        assertEquals(1, sessionRepository.terminalUpdates.size)
        val terminalUpdate = sessionRepository.terminalUpdates.single()
        assertEquals(SchedulerFailureCode.SCHEDULER_EXECUTION_EXCEPTION, terminalUpdate.lastErrorCode)
        assertEquals(0, terminalUpdate.totalCycles)
        assertEquals(0, terminalUpdate.successCycles)
        assertEquals(0, terminalUpdate.failedCycles)
        assertTrue(sessionRepository.runningSessions.containsKey("continuous-task-b"))
        assertTrue(!sessionRepository.runningSessions.containsKey("continuous-task-a"))
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
    fun shouldResetContinuousCursorWhenCredentialSetChangesDuringRunningSession() = runBlocking {
        val taskWithNewCredentialSetJson = continuousTaskJson.replace("\"smoke-set-a\"", "\"smoke-set-b\"")
        val taskRepository = FakeTaskRepository(
            definitions = mutableListOf(
                TaskDefinitionRecord(
                    taskId = "continuous-task",
                    name = "Continuous Task",
                    enabled = true,
                    triggerType = "continuous",
                    definitionStatus = "ready",
                    rawJson = taskWithNewCredentialSetJson,
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
            credentialSet = CredentialSetRecord(
                credentialSetId = "smoke-set-b",
                name = "Smoke Set B",
                description = null,
                strategy = "round_robin",
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
                items = listOf(
                    CredentialSetItemRecord("smoke-set-b", "profile-c", 0, true),
                    CredentialSetItemRecord("smoke-set-b", "profile-d", 1, true),
                ),
            ),
            enabledProfiles = listOf(
                CredentialProfileRecord(
                    profileId = "profile-c",
                    alias = "Alias C",
                    tagsJson = "[]",
                    enabled = true,
                    createdAt = 1L,
                    updatedAt = 1L,
                ),
                CredentialProfileRecord(
                    profileId = "profile-d",
                    alias = "Alias D",
                    tagsJson = "[]",
                    enabled = true,
                    createdAt = 1L,
                    updatedAt = 1L,
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

        assertEquals("smoke-set-b", recorder.lastResult?.taskRun?.credentialSetId)
        assertEquals("profile-c", recorder.lastResult?.taskRun?.credentialProfileId)
        assertEquals("Alias C", recorder.lastResult?.taskRun?.credentialAlias)
        val runningSession = sessionRepository.runningSessions.getValue("continuous-task")
        assertEquals("smoke-set-b", runningSession.credentialSetId)
        assertEquals("profile-c", runningSession.currentCredentialProfileId)
        assertEquals("Alias C", runningSession.currentCredentialAlias)
        assertEquals("profile-d", runningSession.nextCredentialProfileId)
        assertEquals("Alias D", runningSession.nextCredentialAlias)
        assertEquals(1, runningSession.cursorIndex)
    }

    @Test
    fun shouldRestartFromFirstCredentialOnRecoveryWhenPersistCursorIsFalse() = runBlocking {
        val nonPersistentCursorTaskJson = continuousTaskJson.replace("\"persistCursor\": true", "\"persistCursor\": false")
        val taskRepository = FakeTaskRepository(
            definitions = mutableListOf(
                TaskDefinitionRecord(
                    taskId = "continuous-task",
                    name = "Continuous Task",
                    enabled = true,
                    triggerType = "continuous",
                    definitionStatus = "ready",
                    rawJson = nonPersistentCursorTaskJson,
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

        scheduler.dispatchDueTasks(mode = SchedulerDispatchMode.RECOVERY)

        assertEquals("profile-a", recorder.lastResult?.taskRun?.credentialProfileId)
        assertEquals("Alias A", recorder.lastResult?.taskRun?.credentialAlias)
    }

    @Test
    fun shouldResumeContinuousRotationByNextProfileIdWhenCredentialSetOrderChanges() = runBlocking {
        val reorderedCredentialSet = CredentialSetRecord(
            credentialSetId = "smoke-set-a",
            name = "Smoke Set",
            description = null,
            strategy = "round_robin",
            enabled = true,
            createdAt = 1L,
            updatedAt = 1L,
            items = listOf(
                CredentialSetItemRecord("smoke-set-a", "profile-b", 0, true),
                CredentialSetItemRecord("smoke-set-a", "profile-a", 1, true),
            ),
        )
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
            credentialSet = reorderedCredentialSet,
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
    fun shouldTimeoutContinuousSessionBeforeExecutingWhenMaxDurationAlreadyReached() = runBlocking {
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
                    nextTriggerAt = 601_000L,
                    standbyEnabled = true,
                    lastTriggerAt = 1_000L,
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
                currentCredentialProfileId = "profile-a",
                currentCredentialAlias = "profile-a",
                nextCredentialProfileId = "profile-a",
                nextCredentialAlias = "profile-a",
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
            timeSource = FixedSchedulerTimeSource(nowMs = 601_000L),
            cronScheduleCalculator = CronScheduleCalculator(),
            sessionIdFactory = { "session-unused" },
        )

        val result = scheduler.dispatchDueTasks()

        assertTrue(result.executedTaskIds.isEmpty())
        assertTrue(runner.invocations.isEmpty())
        assertEquals(1, recorder.recordCount)
        assertEquals(TaskRunStatus.TIMED_OUT, recorder.lastResult?.taskRun?.status)
        assertEquals(SchedulerFailureCode.SCHEDULER_MAX_DURATION_REACHED, recorder.lastResult?.taskRun?.errorCode)
        assertEquals(3, recorder.lastCycleNo)
        assertEquals(
            "DIAG_SCREENSHOT_NOT_CAPTURED_BEFORE_EXECUTION_TIMEOUT",
            recorder.lastResult?.taskRun?.artifactReason(),
        )
        assertEquals(1, sessionRepository.terminalUpdates.size)
        val terminalUpdate = sessionRepository.terminalUpdates.single()
        assertEquals(TaskRunStatus.TIMED_OUT, terminalUpdate.status)
        assertEquals(SchedulerFailureCode.SCHEDULER_MAX_DURATION_REACHED, terminalUpdate.lastErrorCode)
        assertEquals(2, terminalUpdate.totalCycles)
        assertEquals(2, terminalUpdate.successCycles)
        assertEquals(0, terminalUpdate.failedCycles)
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
        assertEquals(1, recorder.recordCount)
        assertEquals(TaskRunStatus.BLOCKED, recorder.lastResult?.taskRun?.status)
        assertEquals(SchedulerFailureCode.SCHEDULER_CREDENTIAL_SET_UNAVAILABLE, recorder.lastResult?.taskRun?.errorCode)
        assertEquals(3, recorder.lastCycleNo)
        assertEquals(
            "DIAG_SCREENSHOT_NOT_CAPTURED_BEFORE_FIRST_ACTION_BLOCK",
            recorder.lastResult?.taskRun?.artifactReason(),
        )
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
        private val failureTaskIds: Set<String> = emptySet(),
        private val cancellationTaskIds: Set<String> = emptySet(),
    ) : TaskRunner {
        val invocations = mutableListOf<Invocation>()

        override suspend fun run(task: com.plearn.appcontrol.dsl.TaskDefinition, triggerType: String): TaskExecutionResult {
            invocations += Invocation(task.taskId, triggerType)
            if (task.taskId in cancellationTaskIds) {
                throw CancellationException("runner cancellation for ${task.taskId}")
            }
            if (task.taskId in failureTaskIds) {
                throw IllegalStateException("runner failure for ${task.taskId}")
            }
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

    private class RecordingTaskExecutionRecorder(
        private val failureOnRecordCount: Int? = null,
    ) : TaskExecutionRecorder {
        var recordCount: Int = 0
        var lastSessionId: String? = null
        var lastCycleNo: Int? = null
        var lastResult: TaskExecutionResult? = null
        val recordedResults = mutableListOf<TaskExecutionResult>()

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
            recordedResults += persistedResult
            if (failureOnRecordCount == recordCount) {
                throw IllegalStateException("recorder failure")
            }
            return persistedResult
        }
    }

    private fun TaskRunRecord.artifactReason(): String? = Json.parseToJsonElement(artifactsJson)
        .jsonObject["reason"]
        ?.jsonPrimitive
        ?.content

    private fun TaskRunRecord.artifactType(): String? = Json.parseToJsonElement(artifactsJson)
        .jsonObject["artifactType"]
        ?.jsonPrimitive
        ?.content

    private fun TaskRunRecord.artifactRelativePath(): String? = Json.parseToJsonElement(artifactsJson)
        .jsonObject["relativePath"]
        ?.jsonPrimitive
        ?.content

    private class RecordingDiagnosticsArtifactCaptureGate(
        private val allowed: Boolean,
    ) : DiagnosticsArtifactCaptureGate {
        val invocations = mutableListOf<Pair<String?, String?>>()

        override suspend fun canCaptureFailureArtifact(taskId: String?, runId: String?): Boolean {
            invocations += taskId to runId
            return allowed
        }
    }

    private class RecordingCapabilityFacade(
        private val screenshotResult: CapabilityResult<ScreenshotCapture>? = null,
    ) : CapabilityFacade {
        val screenshotRequests = mutableListOf<ScreenshotCaptureRequest>()

        override suspend fun startApp(packageName: String): CapabilityResult<Unit> = throw AssertionError("Unexpected capability call")

        override suspend fun stopApp(packageName: String): CapabilityResult<Unit> = throw AssertionError("Unexpected capability call")

        override suspend fun restartApp(packageName: String, waitAfterStopMs: Long): CapabilityResult<Unit> = throw AssertionError("Unexpected capability call")

        override suspend fun tap(target: TapTarget): CapabilityResult<Unit> = throw AssertionError("Unexpected capability call")

        override suspend fun swipe(request: SwipeRequest): CapabilityResult<Unit> = throw AssertionError("Unexpected capability call")

        override suspend fun inputText(request: InputTextRequest): CapabilityResult<InputTextSummary> = throw AssertionError("Unexpected capability call")

        override suspend fun captureScreenshot(request: ScreenshotCaptureRequest): CapabilityResult<ScreenshotCapture> {
            screenshotRequests += request
            return screenshotResult ?: CapabilityResult.Success(
                ScreenshotCapture(
                    relativePath = "${request.taskId}/${request.runId}/run.png",
                    mimeType = "image/png",
                    fileSizeBytes = 128L,
                ),
            )
        }

        override suspend fun waitForElement(
            selector: ElementSelector,
            state: WaitElementState,
            timeoutMs: Long,
        ): CapabilityResult<Unit> = throw AssertionError("Unexpected capability call")
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

        override suspend fun getCredentialProfile(profileId: String): com.plearn.appcontrol.data.model.CredentialProfileRecord? =
            enabledProfiles.firstOrNull { it.profileId == profileId }

        override suspend fun listCredentialProfiles(): List<com.plearn.appcontrol.data.model.CredentialProfileRecord> =
            getEnabledProfiles()

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

    override suspend fun listCredentialSets(): List<CredentialSetRecord> = listOfNotNull(credentialSet)

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

        override suspend fun findRunningSessions(): List<ContinuousSessionRecord> =
            runningSessions.values.sortedByDescending { it.startedAt }

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

        override suspend fun delay(durationMs: Long) = Unit
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
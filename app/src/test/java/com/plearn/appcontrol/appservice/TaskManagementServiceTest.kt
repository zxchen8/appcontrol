package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.data.model.TaskScheduleStateRecord
import com.plearn.appcontrol.data.repository.TaskRepository
import com.plearn.appcontrol.dsl.TaskDefinition
import com.plearn.appcontrol.dsl.TaskDslParser
import com.plearn.appcontrol.runner.RunTriggerType
import com.plearn.appcontrol.runner.RunnerTimeSource
import com.plearn.appcontrol.runner.TaskExecutionRecorder
import com.plearn.appcontrol.runner.TaskExecutionResult
import com.plearn.appcontrol.runner.TaskRunStatus
import com.plearn.appcontrol.runner.TaskRunner
import com.plearn.appcontrol.scheduler.CronScheduleCalculator
import com.plearn.appcontrol.scheduler.SchedulerTimeSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskManagementServiceTest {
    @Test
    fun shouldImportReadyCronTaskPersistScheduleStateAndStartScheduler() = runBlocking {
        val taskRepository = FakeTaskRepository()
        val standbyController = RecordingSchedulerStandbyController()
        val service = TaskManagementService(
            taskRepository = taskRepository,
            parser = TaskDslParser(),
            manualTaskExecutionService = buildManualTaskExecutionService(),
            schedulerTimeSource = FixedSchedulerTimeSource(nowMs = 1_000L),
            cronScheduleCalculator = CronScheduleCalculator(),
            schedulerStandbyController = standbyController,
        )

        val result = service.importTask(validCronTaskJson)

        assertTrue(result.saved)
        assertEquals("cron-task", result.taskId)
        val stored = taskRepository.getTaskDefinition("cron-task")
        assertNotNull(stored)
        assertEquals("ready", stored?.definitionStatus)
        val scheduleState = taskRepository.getScheduleState("cron-task")
        assertNotNull(scheduleState)
        assertTrue(scheduleState!!.standbyEnabled)
        assertTrue(scheduleState.nextTriggerAt!! > 1_000L)
        assertEquals(listOf(true), standbyController.syncCalls)
    }

    @Test
    fun shouldReturnValidationErrorsWithoutPersistingInvalidTask() = runBlocking {
        val taskRepository = FakeTaskRepository()
        val standbyController = RecordingSchedulerStandbyController()
        val service = TaskManagementService(
            taskRepository = taskRepository,
            parser = TaskDslParser(),
            manualTaskExecutionService = buildManualTaskExecutionService(),
            schedulerTimeSource = FixedSchedulerTimeSource(nowMs = 1_000L),
            cronScheduleCalculator = CronScheduleCalculator(),
            schedulerStandbyController = standbyController,
        )

        val result = service.importTask("{}")

        assertFalse(result.saved)
        assertTrue(result.errors.isNotEmpty())
        assertTrue(taskRepository.definitions.isEmpty())
        assertTrue(standbyController.syncCalls.isEmpty())
    }

    @Test
    fun shouldRejectInvalidCronTimezoneWithoutPersistingTask() = runBlocking {
        val taskRepository = FakeTaskRepository()
        val standbyController = RecordingSchedulerStandbyController()
        val service = TaskManagementService(
            taskRepository = taskRepository,
            parser = TaskDslParser(),
            manualTaskExecutionService = buildManualTaskExecutionService(),
            schedulerTimeSource = FixedSchedulerTimeSource(nowMs = 1_000L),
            cronScheduleCalculator = CronScheduleCalculator(),
            schedulerStandbyController = standbyController,
        )

        val result = service.importTask(validCronTaskJson.replace("Asia/Shanghai", "Mars/OlympusMons"))

        assertFalse(result.saved)
        assertTrue(result.errors.any { it.contains("trigger.timezone") })
        assertTrue(taskRepository.definitions.isEmpty())
        assertTrue(taskRepository.scheduleStates.isEmpty())
        assertTrue(standbyController.syncCalls.isEmpty())
    }

    @Test
    fun shouldToggleTaskEnabledRewriteRawJsonAndStopSchedulerWhenNoActiveTasksRemain() = runBlocking {
        val taskRepository = FakeTaskRepository(
            definitions = mutableListOf(
                TaskDefinitionRecord(
                    taskId = "cron-task",
                    name = "Cron Task",
                    enabled = true,
                    triggerType = "cron",
                    definitionStatus = "ready",
                    rawJson = validCronTaskJson,
                    updatedAt = 100L,
                ),
            ),
            scheduleStates = mutableMapOf(
                "cron-task" to TaskScheduleStateRecord(
                    taskId = "cron-task",
                    nextTriggerAt = 5_000L,
                    standbyEnabled = true,
                    lastTriggerAt = 1_000L,
                    lastScheduleStatus = "scheduled",
                ),
            ),
        )
        val standbyController = RecordingSchedulerStandbyController()
        val service = TaskManagementService(
            taskRepository = taskRepository,
            parser = TaskDslParser(),
            manualTaskExecutionService = buildManualTaskExecutionService(),
            schedulerTimeSource = FixedSchedulerTimeSource(nowMs = 2_000L),
            cronScheduleCalculator = CronScheduleCalculator(),
            schedulerStandbyController = standbyController,
        )

        val result = service.setTaskEnabled(taskId = "cron-task", enabled = false)

        assertTrue(result.updated)
        val stored = taskRepository.getTaskDefinition("cron-task")
        assertNotNull(stored)
        assertFalse(stored!!.enabled)
        val enabledField = Json.parseToJsonElement(stored.rawJson).jsonObject["enabled"]?.jsonPrimitive?.boolean
        assertEquals(false, enabledField)
        val scheduleState = taskRepository.getScheduleState("cron-task")
        assertNotNull(scheduleState)
        assertFalse(scheduleState!!.standbyEnabled)
        assertNull(scheduleState.nextTriggerAt)
        assertEquals(listOf(false), standbyController.syncCalls)
    }

    @Test
    fun shouldRunStoredTaskThroughManualExecutionService() = runBlocking {
        val taskRepository = FakeTaskRepository(
            definitions = mutableListOf(
                TaskDefinitionRecord(
                    taskId = "cron-task",
                    name = "Cron Task",
                    enabled = true,
                    triggerType = "cron",
                    definitionStatus = "ready",
                    rawJson = validCronTaskJson,
                    updatedAt = 100L,
                ),
            ),
        )
        val runner = RecordingTaskRunner()
        val service = TaskManagementService(
            taskRepository = taskRepository,
            parser = TaskDslParser(),
            manualTaskExecutionService = buildManualTaskExecutionService(runner = runner),
            schedulerTimeSource = FixedSchedulerTimeSource(nowMs = 1_000L),
            cronScheduleCalculator = CronScheduleCalculator(),
            schedulerStandbyController = RecordingSchedulerStandbyController(),
        )

        val result = service.runTaskNow("cron-task")

        assertNotNull(result.execution)
        assertEquals(TaskRunStatus.SUCCESS, result.execution?.taskRun?.status)
        assertEquals("cron-task", runner.lastTask?.taskId)
        assertEquals(RunTriggerType.MANUAL, runner.lastTriggerType)
    }

    private fun buildManualTaskExecutionService(
        runner: RecordingTaskRunner = RecordingTaskRunner(),
    ): ManualTaskExecutionService = ManualTaskExecutionService(
        parser = TaskDslParser(),
        taskRunner = runner,
        executionRecorder = PassthroughTaskExecutionRecorder(),
        timeSource = FixedRunnerTimeSource(),
        runIdFactory = { "manual-run" },
    )

    private class FakeTaskRepository(
        val definitions: MutableList<TaskDefinitionRecord> = mutableListOf(),
        val scheduleStates: MutableMap<String, TaskScheduleStateRecord> = mutableMapOf(),
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

    private class RecordingSchedulerStandbyController : SchedulerStandbyController {
        val syncCalls = mutableListOf<Boolean>()

        override fun syncActiveSchedules(hasActiveSchedules: Boolean) {
            syncCalls += hasActiveSchedules
        }
    }

    private class FixedSchedulerTimeSource(
        private val nowMs: Long,
    ) : SchedulerTimeSource {
        override fun nowMs(): Long = nowMs

        override suspend fun delay(durationMs: Long) = Unit
    }

    private class FixedRunnerTimeSource : RunnerTimeSource {
        override fun nowMs(): Long = 1_000L

        override suspend fun delay(durationMs: Long) = Unit
    }

    private class PassthroughTaskExecutionRecorder : TaskExecutionRecorder {
        override suspend fun record(
            result: TaskExecutionResult,
            sessionId: String?,
            cycleNo: Int?,
        ): TaskExecutionResult = result
    }

    private class RecordingTaskRunner : TaskRunner {
        var lastTask: TaskDefinition? = null
        var lastTriggerType: String? = null

        override suspend fun run(task: TaskDefinition, triggerType: String): TaskExecutionResult {
            lastTask = task
            lastTriggerType = triggerType
            return TaskExecutionResult(
                taskRun = TaskRunRecord(
                    runId = "run-1",
                    sessionId = null,
                    cycleNo = null,
                    taskId = task.taskId,
                    credentialSetId = null,
                    credentialProfileId = null,
                    credentialAlias = null,
                    status = TaskRunStatus.SUCCESS,
                    startedAt = 1_000L,
                    finishedAt = 1_100L,
                    durationMs = 100L,
                    triggerType = triggerType,
                    errorCode = null,
                    message = null,
                ),
                stepRuns = listOf(
                    StepRunRecord(
                        runId = "run-1",
                        stepId = "step-1",
                        status = "success",
                        startedAt = 1_000L,
                        finishedAt = 1_050L,
                        durationMs = 50L,
                        errorCode = null,
                        message = null,
                        artifactsJson = "{}",
                    ),
                ),
                taskAttemptCount = 1,
            )
        }
    }

    private companion object {
        val validCronTaskJson = """
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
                "expression": "*/5 * * * *",
                "timezone": "Asia/Shanghai"
              },
              "executionPolicy": {
                "taskTimeoutMs": 60000,
                "maxRetries": 0,
                "retryBackoffMs": 1000,
                "conflictPolicy": "skip",
                "onMissedSchedule": "skip"
              },
              "steps": [
                {
                  "id": "step-start",
                  "type": "start_app",
                  "timeoutMs": 5000,
                  "params": {
                    "packageName": "com.example.target"
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
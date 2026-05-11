package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.dsl.TaskDefinition
import com.plearn.appcontrol.dsl.TaskDslParser
import com.plearn.appcontrol.runner.RunTriggerType
import com.plearn.appcontrol.runner.RunnerFailureCode
import com.plearn.appcontrol.runner.RunnerTimeSource
import com.plearn.appcontrol.runner.StepRunStatus
import com.plearn.appcontrol.runner.TaskExecutionResult
import com.plearn.appcontrol.runner.TaskExecutionRecorder
import com.plearn.appcontrol.runner.TaskRunStatus
import com.plearn.appcontrol.runner.TaskRunner
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManualTaskExecutionServiceTest {
    @Test
    fun shouldBlockManualRunWhenDefinitionStatusIsNotReady() = runBlocking {
        val runner = RecordingTaskRunner()
        val recorder = RecordingTaskExecutionRecorder()
        val service = ManualTaskExecutionService(
            parser = TaskDslParser(),
            taskRunner = runner,
            executionRecorder = recorder,
            timeSource = FixedRunnerTimeSource(),
            runIdFactory = { "blocked-run" },
        )

        val result = service.run(
            TaskDefinitionRecord(
                taskId = "task-01",
                name = "Bad task",
                enabled = true,
                triggerType = "cron",
                definitionStatus = "invalid",
                rawJson = "{}",
                updatedAt = 100L,
            ),
        )

        assertEquals(TaskRunStatus.BLOCKED, result.taskRun.status)
        assertEquals(RunnerFailureCode.RUNNER_TASK_NOT_READY, result.taskRun.errorCode)
        assertEquals(
            "DIAG_SCREENSHOT_NOT_CAPTURED_BEFORE_FIRST_ACTION_BLOCK",
            result.taskRun.artifactReason(),
        )
        assertTrue(result.stepRuns.isEmpty())
        assertEquals(0, runner.invocationCount)
        assertEquals(0, recorder.recordCount)
    }

    @Test
    fun shouldBlockManualRunWhenDslIsInvalid() = runBlocking {
        val runner = RecordingTaskRunner()
        val recorder = RecordingTaskExecutionRecorder()
        val service = ManualTaskExecutionService(
            parser = TaskDslParser(),
            taskRunner = runner,
            executionRecorder = recorder,
            timeSource = FixedRunnerTimeSource(),
            runIdFactory = { "invalid-dsl-run" },
        )

        val result = service.run(
            TaskDefinitionRecord(
                taskId = "task-02",
                name = "Invalid DSL task",
                enabled = true,
                triggerType = "manual",
                definitionStatus = "ready",
                rawJson = "{\"schemaVersion\":\"1.0\",\"taskId\":\"task-02\"}",
                updatedAt = 101L,
            ),
        )

        assertEquals(TaskRunStatus.BLOCKED, result.taskRun.status)
        assertEquals(RunnerFailureCode.RUNNER_DSL_INVALID, result.taskRun.errorCode)
        assertEquals(
            "DIAG_SCREENSHOT_NOT_CAPTURED_BEFORE_FIRST_ACTION_BLOCK",
            result.taskRun.artifactReason(),
        )
        assertEquals(0, runner.invocationCount)
        assertEquals(0, recorder.recordCount)
    }

    @Test
    fun shouldParseReadyRecordAndDelegateToRunner() = runBlocking {
        val runner = RecordingTaskRunner()
        val recorder = RecordingTaskExecutionRecorder()
        val service = ManualTaskExecutionService(
            parser = TaskDslParser(),
            taskRunner = runner,
            executionRecorder = recorder,
            timeSource = FixedRunnerTimeSource(),
            runIdFactory = { "manual-run" },
        )

        val result = service.run(
            TaskDefinitionRecord(
                taskId = "smoke-login",
                name = "Smoke Login",
                enabled = true,
                triggerType = "cron",
                definitionStatus = "ready",
                rawJson = validTaskJson,
                updatedAt = 200L,
            ),
        )

        assertEquals(1, runner.invocationCount)
        assertNotNull(runner.lastTask)
        assertEquals(RunTriggerType.MANUAL, runner.lastTriggerType)
        assertEquals(TaskRunStatus.SUCCESS, result.taskRun.status)
        assertEquals(listOf(StepRunStatus.SUCCESS), result.stepRuns.map { it.status })
        assertEquals(1, recorder.recordCount)
    }

    @Test
    fun shouldPersistRecordedManualRunAfterRunnerCompletes() = runBlocking {
        val runner = RecordingTaskRunner()
        val recorder = RecordingTaskExecutionRecorder()
        val service = ManualTaskExecutionService(
            parser = TaskDslParser(),
            taskRunner = runner,
            executionRecorder = recorder,
            timeSource = FixedRunnerTimeSource(),
            runIdFactory = { "persisted-manual-run" },
        )

        val result = service.run(
            TaskDefinitionRecord(
                taskId = "smoke-login",
                name = "Smoke Login",
                enabled = true,
                triggerType = "cron",
                definitionStatus = "ready",
                rawJson = validTaskJson,
                updatedAt = 200L,
            ),
        )

        assertEquals(1, recorder.recordCount)
        assertEquals(result.taskRun.runId, recorder.lastResult?.taskRun?.runId)
    }

    @Test
    fun shouldRecordFailedManualRunWhenRunnerThrowsException() = runBlocking {
        val runner = RecordingTaskRunner(shouldThrow = true)
        val recorder = RecordingTaskExecutionRecorder()
        val service = ManualTaskExecutionService(
            parser = TaskDslParser(),
            taskRunner = runner,
            executionRecorder = recorder,
            timeSource = FixedRunnerTimeSource(),
            runIdFactory = { "manual-execution-exception" },
        )

        val result = service.run(
            TaskDefinitionRecord(
                taskId = "smoke-login",
                name = "Smoke Login",
                enabled = true,
                triggerType = "cron",
                definitionStatus = "ready",
                rawJson = validTaskJson,
                updatedAt = 200L,
            ),
        )

        assertEquals(1, runner.invocationCount)
    assertEquals(TaskRunStatus.FAILED, result.taskRun.status)
        assertEquals(RunnerFailureCode.RUNNER_EXECUTION_EXCEPTION, result.taskRun.errorCode)
        assertEquals("screenshot_unavailable", result.taskRun.artifactType())
        assertEquals("DIAG_SCREENSHOT_UNAVAILABLE_FOR_EXECUTION_EXCEPTION", result.taskRun.artifactReason())
        assertEquals(null, result.taskRun.artifactRelativePath())
        assertEquals(1, recorder.recordCount)
        assertEquals(result.taskRun.runId, recorder.lastResult?.taskRun?.runId)
        assertEquals(
            "Manual execution for smoke-login failed with an execution exception.",
            result.taskRun.message,
        )
    }

    @Test
    fun shouldSkipManualExecutionExceptionScreenshotWhenDiagnosticsDisableCapture() = runBlocking {
        val runner = RecordingTaskRunner(shouldThrow = true)
        val recorder = RecordingTaskExecutionRecorder()
        val service = ManualTaskExecutionService(
            parser = TaskDslParser(),
            taskRunner = runner,
            executionRecorder = recorder,
            timeSource = FixedRunnerTimeSource(),
            runIdFactory = { "manual-diagnostics-disabled" },
        )

        val result = service.run(
            TaskDefinitionRecord(
                taskId = "smoke-login",
                name = "Smoke Login",
                enabled = true,
                triggerType = "cron",
                definitionStatus = "ready",
                rawJson = validTaskJsonWithFailureScreenshotDisabled,
                updatedAt = 200L,
            ),
        )

        assertEquals(TaskRunStatus.FAILED, result.taskRun.status)
        assertEquals("screenshot_skipped", result.taskRun.artifactType())
        assertEquals("DIAG_SCREENSHOT_CAPTURE_DISABLED_BY_POLICY", result.taskRun.artifactReason())
        assertEquals(null, result.taskRun.artifactRelativePath())
    }

    @Test
    fun shouldPropagateRecorderFailureAfterRunnerCompletesWithoutSynthesizingFallback() = runBlocking {
        val runner = RecordingTaskRunner()
        val recorder = RecordingTaskExecutionRecorder(failureOnRecordCount = 1)
        val service = ManualTaskExecutionService(
            parser = TaskDslParser(),
            taskRunner = runner,
            executionRecorder = recorder,
            timeSource = FixedRunnerTimeSource(),
            runIdFactory = { "manual-recorder-failure" },
        )

        try {
            service.run(
                TaskDefinitionRecord(
                    taskId = "smoke-login",
                    name = "Smoke Login",
                    enabled = true,
                    triggerType = "cron",
                    definitionStatus = "ready",
                    rawJson = validTaskJson,
                    updatedAt = 200L,
                ),
            )
            fail("Expected recorder failure to be propagated.")
        } catch (error: IllegalStateException) {
            assertEquals("recorder failure", error.message)
        }

        assertEquals(1, runner.invocationCount)
    }

    @Test
    fun shouldRetainRunnerExceptionAsSuppressedWhenSyntheticRecordFails() = runBlocking {
        val runner = RecordingTaskRunner(shouldThrow = true)
        val recorder = RecordingTaskExecutionRecorder(failureOnRecordCount = 1)
        val service = ManualTaskExecutionService(
            parser = TaskDslParser(),
            taskRunner = runner,
            executionRecorder = recorder,
            timeSource = FixedRunnerTimeSource(),
            runIdFactory = { "manual-synthetic-recorder-failure" },
        )

        try {
            service.run(
                TaskDefinitionRecord(
                    taskId = "smoke-login",
                    name = "Smoke Login",
                    enabled = true,
                    triggerType = "cron",
                    definitionStatus = "ready",
                    rawJson = validTaskJson,
                    updatedAt = 200L,
                ),
            )
            fail("Expected recorder failure to be propagated.")
        } catch (error: IllegalStateException) {
            assertEquals("recorder failure", error.message)
            assertEquals(1, error.suppressed.size)
            assertEquals("runner execution failed", error.suppressed.single().message)
        }

        assertEquals(1, runner.invocationCount)
        assertEquals(1, recorder.recordCount)
    }

    private class RecordingTaskRunner(
        private val shouldThrow: Boolean = false,
    ) : TaskRunner {
        var invocationCount: Int = 0
        var lastTask: TaskDefinition? = null
        var lastTriggerType: String? = null

        override suspend fun run(task: TaskDefinition, triggerType: String): TaskExecutionResult {
            invocationCount += 1
            lastTask = task
            lastTriggerType = triggerType
            if (shouldThrow) {
                throw IllegalStateException("runner execution failed")
            }
            return TaskExecutionResult(
                taskRun = TaskRunRecord(
                    runId = "run-123",
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
                    com.plearn.appcontrol.data.model.StepRunRecord(
                        runId = "run-123",
                        stepId = "step-start",
                        status = StepRunStatus.SUCCESS,
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

    private class FixedRunnerTimeSource : RunnerTimeSource {
        override fun nowMs(): Long = 1_000L

        override suspend fun delay(durationMs: Long) = Unit
    }

    private class RecordingTaskExecutionRecorder(
        private val failureOnRecordCount: Int? = null,
    ) : TaskExecutionRecorder {
        var recordCount: Int = 0
        var lastResult: TaskExecutionResult? = null

        override suspend fun record(
            result: TaskExecutionResult,
            sessionId: String?,
            cycleNo: Int?,
        ): TaskExecutionResult {
            recordCount += 1
            lastResult = result
            if (failureOnRecordCount == recordCount) {
                throw IllegalStateException("recorder failure")
            }
            return result
        }
    }

    private companion object {
        val validTaskJson = """
            {
              "schemaVersion": "1.0",
              "taskId": "smoke-login",
              "name": "Smoke Login",
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
                  "retry": { "maxRetries": 0, "backoffMs": 1000 },
                  "onFailure": "stop_task",
                  "params": {
                    "packageName": "com.example.target"
                  }
                }
              ]
            }
        """.trimIndent()

        val validTaskJsonWithFailureScreenshotDisabled = validTaskJson.replace(
            "\"steps\": [",
            "\"diagnostics\": {\n                \"captureScreenshotOnFailure\": false,\n                \"captureScreenshotOnStepFailure\": true,\n                \"logLevel\": \"info\"\n              },\n              \"steps\": [",
        )
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
}
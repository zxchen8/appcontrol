package com.plearn.appcontrol.runner

import com.plearn.appcontrol.capability.CapabilityFailureCode
import com.plearn.appcontrol.capability.CapabilityFacade
import com.plearn.appcontrol.capability.CapabilityResult
import com.plearn.appcontrol.capability.ElementSelector
import com.plearn.appcontrol.capability.InputTextRequest
import com.plearn.appcontrol.capability.InputTextSource
import com.plearn.appcontrol.capability.InputTextSummary
import com.plearn.appcontrol.capability.ScreenPoint
import com.plearn.appcontrol.capability.SelectorType
import com.plearn.appcontrol.capability.SwipeRequest
import com.plearn.appcontrol.capability.TapTarget
import com.plearn.appcontrol.capability.WaitElementState
import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.dsl.ConflictPolicy
import com.plearn.appcontrol.dsl.DiagnosticsPolicy
import com.plearn.appcontrol.dsl.ExecutionPolicy
import com.plearn.appcontrol.dsl.MissedSchedulePolicy
import com.plearn.appcontrol.dsl.StepFailurePolicy
import com.plearn.appcontrol.dsl.StepRetryPolicy
import com.plearn.appcontrol.dsl.StepType
import com.plearn.appcontrol.dsl.TargetApp
import com.plearn.appcontrol.dsl.TaskDefinition
import com.plearn.appcontrol.dsl.TaskStep
import com.plearn.appcontrol.dsl.TaskTrigger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultTaskRunnerTest {
    @Test
    fun shouldExecuteSupportedManualStepsSequentiallyAndReturnSuccessTaskRun() = runBlocking {
        val facade = RecordingCapabilityFacade()
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            runIdFactory = { "run-001" },
        )

        val result = runner.run(
            task = sampleTask(
                steps = listOf(
                    startAppStep(),
                    tapElementStep(),
                    inputTextStep(text = "hello@123"),
                    waitElementStep(),
                    stopAppStep(),
                ),
            ),
            triggerType = RunTriggerType.MANUAL,
        )

        assertEquals("run-001", result.taskRun.runId)
        assertEquals(TaskRunStatus.SUCCESS, result.taskRun.status)
        assertEquals(RunTriggerType.MANUAL, result.taskRun.triggerType)
        assertEquals(
            listOf(
                "start:com.example.target",
                "tap:text=登录",
                "input:hello@123:false:LITERAL",
                "wait:text=首页:APPEARED:3000",
                "stop:com.example.target",
            ),
            facade.operations,
        )
        assertEquals(
            listOf(
                StepRunStatus.SUCCESS,
                StepRunStatus.SUCCESS,
                StepRunStatus.SUCCESS,
                StepRunStatus.SUCCESS,
                StepRunStatus.SUCCESS,
            ),
            result.stepRuns.map(StepRunRecord::status),
        )
        assertEquals(1, result.taskAttemptCount)
    }

    @Test
    fun shouldRetryOnlyFailingStepBeforeContinuingToNextStep() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            tapResults += CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_ELEMENT_NOT_FOUND,
                message = "not found",
            )
            tapResults += CapabilityResult.Success(Unit)
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            runIdFactory = { "run-002" },
        )

        val result = runner.run(
            task = sampleTask(
                steps = listOf(
                    startAppStep(),
                    tapElementStep(retry = StepRetryPolicy(maxRetries = 1, backoffMs = 250)),
                    stopAppStep(),
                ),
            ),
        )

        assertEquals(TaskRunStatus.SUCCESS, result.taskRun.status)
        assertEquals(
            listOf(
                "start:com.example.target",
                "tap:text=登录",
                "tap:text=登录",
                "stop:com.example.target",
            ),
            facade.operations,
        )
        assertEquals(
            listOf(
                StepRunStatus.SUCCESS,
                StepRunStatus.FAILED,
                StepRunStatus.SUCCESS,
                StepRunStatus.SUCCESS,
            ),
            result.stepRuns.map(StepRunRecord::status),
        )
    }

    @Test
    fun shouldRestartTaskFromFirstStepWhenFatalStepConsumesTaskRetry() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            waitResults += CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_EXECUTION_FAILED,
                message = "still loading",
            )
            waitResults += CapabilityResult.Success(Unit)
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            runIdFactory = { "run-003" },
        )

        val result = runner.run(
            task = sampleTask(
                executionPolicy = defaultExecutionPolicy(maxRetries = 1, retryBackoffMs = 500),
                steps = listOf(
                    startAppStep(),
                    waitElementStep(timeoutMs = 5_000),
                    stopAppStep(),
                ),
            ),
        )

        assertEquals(TaskRunStatus.SUCCESS, result.taskRun.status)
        assertEquals(2, result.taskAttemptCount)
        assertEquals(
            listOf(
                "start:com.example.target",
                "wait:text=首页:APPEARED:5000",
                "start:com.example.target",
                "wait:text=首页:APPEARED:5000",
                "stop:com.example.target",
            ),
            facade.operations,
        )
    }

    @Test
    fun shouldReturnTimedOutWhenStepExceedsTimeout() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            waitDelayMs = 50
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            runIdFactory = { "run-004" },
        )

        val result = runner.run(
            task = sampleTask(
                steps = listOf(waitElementStep(timeoutMs = 5)),
            ),
        )

        assertEquals(TaskRunStatus.TIMED_OUT, result.taskRun.status)
        assertEquals(CapabilityFailureCode.STEP_TIMEOUT, result.taskRun.errorCode)
        assertEquals(StepRunStatus.TIMED_OUT, result.stepRuns.single().status)
    }

    @Test
    fun shouldContinueTaskWhenStepFailurePolicyIsContinue() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            tapResults += CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_ELEMENT_NOT_FOUND,
                message = "login button missing",
            )
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            runIdFactory = { "run-005" },
        )

        val result = runner.run(
            task = sampleTask(
                steps = listOf(
                    tapElementStep(onFailure = StepFailurePolicy.CONTINUE),
                    stopAppStep(),
                ),
            ),
        )

        assertEquals(TaskRunStatus.SUCCESS, result.taskRun.status)
        assertEquals(
            listOf(StepRunStatus.FAILED, StepRunStatus.SUCCESS),
            result.stepRuns.map(StepRunRecord::status),
        )
        assertEquals(
            listOf(
                "tap:text=登录",
                "stop:com.example.target",
            ),
            facade.operations,
        )
    }

    private fun sampleTask(
        executionPolicy: ExecutionPolicy = defaultExecutionPolicy(),
        steps: List<TaskStep>,
    ): TaskDefinition = TaskDefinition(
        schemaVersion = "1.0",
        taskId = "smoke-task",
        name = "Smoke Task",
        description = null,
        enabled = true,
        targetApp = TargetApp(packageName = "com.example.target"),
        trigger = TaskTrigger.Cron(expression = "*/5 * * * *", timezone = "Asia/Shanghai"),
        accountRotation = null,
        executionPolicy = executionPolicy,
        preconditions = emptyList(),
        variables = null,
        steps = steps,
        diagnostics = DiagnosticsPolicy(),
        tags = emptyList(),
    )

    private fun defaultExecutionPolicy(
        maxRetries: Int = 0,
        retryBackoffMs: Long = 1_000,
    ): ExecutionPolicy = ExecutionPolicy(
        taskTimeoutMs = 60_000,
        maxRetries = maxRetries,
        retryBackoffMs = retryBackoffMs,
        conflictPolicy = ConflictPolicy.SKIP,
        onMissedSchedule = MissedSchedulePolicy.SKIP,
    )

    private fun startAppStep(): TaskStep = TaskStep(
        id = "step-start",
        type = StepType.START_APP,
        name = null,
        timeoutMs = 5_000,
        retry = StepRetryPolicy(),
        onFailure = StepFailurePolicy.STOP_TASK,
        params = buildJsonObject {
            put("packageName", "com.example.target")
        },
    )

    private fun stopAppStep(): TaskStep = TaskStep(
        id = "step-stop",
        type = StepType.STOP_APP,
        name = null,
        timeoutMs = 5_000,
        retry = StepRetryPolicy(),
        onFailure = StepFailurePolicy.STOP_TASK,
        params = buildJsonObject {
            put("packageName", "com.example.target")
        },
    )

    private fun tapElementStep(
        retry: StepRetryPolicy = StepRetryPolicy(),
        onFailure: StepFailurePolicy = StepFailurePolicy.STOP_TASK,
    ): TaskStep = TaskStep(
        id = "step-tap-login",
        type = StepType.TAP,
        name = null,
        timeoutMs = 5_000,
        retry = retry,
        onFailure = onFailure,
        params = buildJsonObject {
            putJsonObject("target") {
                put("kind", "element")
                putJsonObject("selector") {
                    put("by", "text")
                    put("value", "登录")
                }
            }
        },
    )

    private fun inputTextStep(text: String): TaskStep = TaskStep(
        id = "step-input",
        type = StepType.INPUT_TEXT,
        name = null,
        timeoutMs = 5_000,
        retry = StepRetryPolicy(),
        onFailure = StepFailurePolicy.STOP_TASK,
        params = buildJsonObject {
            put("text", text)
            put("clearBeforeInput", true)
            putJsonObject("selector") {
                put("by", "resourceId")
                put("value", "com.example.target:id/phone")
            }
        },
    )

    private fun waitElementStep(timeoutMs: Long = 3_000): TaskStep = TaskStep(
        id = "step-wait-home",
        type = StepType.WAIT_ELEMENT,
        name = null,
        timeoutMs = timeoutMs,
        retry = StepRetryPolicy(),
        onFailure = StepFailurePolicy.STOP_TASK,
        params = buildJsonObject {
            put("state", "visible")
            putJsonObject("selector") {
                put("by", "text")
                put("value", "首页")
            }
        },
    )

    private class RecordingCapabilityFacade : CapabilityFacade {
        val operations = mutableListOf<String>()
        val tapResults = ArrayDeque<CapabilityResult<Unit>>()
        val waitResults = ArrayDeque<CapabilityResult<Unit>>()
        var waitDelayMs: Long = 0

        override suspend fun startApp(packageName: String): CapabilityResult<Unit> {
            operations += "start:$packageName"
            return CapabilityResult.Success(Unit)
        }

        override suspend fun stopApp(packageName: String): CapabilityResult<Unit> {
            operations += "stop:$packageName"
            return CapabilityResult.Success(Unit)
        }

        override suspend fun restartApp(packageName: String, waitAfterStopMs: Long): CapabilityResult<Unit> {
            operations += "restart:$packageName@$waitAfterStopMs"
            return CapabilityResult.Success(Unit)
        }

        override suspend fun tap(target: TapTarget): CapabilityResult<Unit> {
            operations += when (target) {
                is TapTarget.Coordinate -> "tap:${target.point.x},${target.point.y}"
                is TapTarget.Element -> "tap:${target.selector.by.name.lowercase()}=${target.selector.value}"
                is TapTarget.Image -> "tap:image:${target.templateId}"
                is TapTarget.OcrText -> "tap:ocr:${target.text}"
            }
            return if (tapResults.isEmpty()) {
                CapabilityResult.Success(Unit)
            } else {
                tapResults.removeFirst()
            }
        }

        override suspend fun swipe(request: SwipeRequest): CapabilityResult<Unit> {
            operations += "swipe:${request.from.x},${request.from.y}->${request.to.x},${request.to.y}@${request.durationMs}"
            return CapabilityResult.Success(Unit)
        }

        override suspend fun inputText(request: InputTextRequest): CapabilityResult<InputTextSummary> {
            operations += "input:${request.text}:${request.masked}:${request.source.name}"
            return CapabilityResult.Success(
                InputTextSummary(
                    selectorSummary = request.selector?.value,
                    source = request.source,
                    masked = request.masked,
                ),
            )
        }

        override suspend fun waitForElement(
            selector: ElementSelector,
            state: WaitElementState,
            timeoutMs: Long,
        ): CapabilityResult<Unit> {
            operations += "wait:${selector.by.name.lowercase()}=${selector.value}:$state:$timeoutMs"
            if (waitDelayMs > 0) {
                delay(waitDelayMs)
            }
            return if (waitResults.isEmpty()) {
                CapabilityResult.Success(Unit)
            } else {
                waitResults.removeFirst()
            }
        }
    }

    private class FakeRunnerTimeSource : RunnerTimeSource {
        private var currentTimeMs: Long = 1_000

        override fun nowMs(): Long = currentTimeMs

        override suspend fun delay(durationMs: Long) {
            currentTimeMs += durationMs
        }
    }
}
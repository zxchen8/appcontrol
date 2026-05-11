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
import com.plearn.appcontrol.capability.ScreenshotCapture
import com.plearn.appcontrol.capability.ScreenshotCaptureRequest
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
                "captureScreenshot:smoke-task:run-002:step-tap-login:1",
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
                "captureScreenshot:smoke-task:run-003:step-wait-home:1",
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
        assertEquals("{}", result.taskRun.artifactsJson)
    }

    @Test
    fun shouldRecordRunLevelArtifactWhenTaskTimesOutBeforeStepResultIsPersisted() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            waitDelayMs = 50
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            runIdFactory = { "run-004b" },
        )

        val result = runner.run(
            task = sampleTask(
                executionPolicy = defaultExecutionPolicy(taskTimeoutMs = 10),
                steps = listOf(waitElementStep(timeoutMs = 5_000)),
            ),
        )

        assertEquals(TaskRunStatus.TIMED_OUT, result.taskRun.status)
        assertEquals(emptyList<StepRunRecord>(), result.stepRuns)
        assertEquals("screenshot", result.taskRun.artifactType())
        assertEquals(expectedRunArtifactPath(runId = "run-004b"), result.taskRun.artifactRelativePath())
    }

    @Test
    fun shouldRecordSuppressedRunLevelArtifactWhenTaskTimesOutAfterSensitiveContextActivated() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            waitDelayMs = 200
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            runIdFactory = { "run-004c" },
        )

        val result = runner.run(
            task = sampleTask(
                executionPolicy = defaultExecutionPolicy(taskTimeoutMs = 50),
                variables = buildJsonObject {
                    putJsonObject("secretPhone") {
                        put("value", "13800138000")
                        put("sensitive", true)
                    }
                },
                steps = listOf(
                    inputTextVariableRefStep("secretPhone"),
                    waitElementStep(timeoutMs = 5_000),
                ),
            ),
        )

        assertEquals(TaskRunStatus.TIMED_OUT, result.taskRun.status)
        assertEquals(listOf(StepRunStatus.SUCCESS), result.stepRuns.map(StepRunRecord::status))
        assertEquals(
            "DIAG_SCREENSHOT_SUPPRESSED_FOR_SENSITIVE_CONTENT",
            result.taskRun.artifactReason(),
        )
    }

    @Test
    fun shouldRecordRunLevelArtifactAfterRecoveredStepRetryBeforeOuterTimeout() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            tapResults += CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_ELEMENT_NOT_FOUND,
                message = "not found",
            )
            tapResults += CapabilityResult.Success(Unit)
            waitDelayMs = 200
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            runIdFactory = { "run-004d" },
        )

        val result = runner.run(
            task = sampleTask(
                executionPolicy = defaultExecutionPolicy(taskTimeoutMs = 50),
                steps = listOf(
                    tapElementStep(retry = StepRetryPolicy(maxRetries = 1, backoffMs = 0)),
                    waitElementStep(timeoutMs = 5_000),
                ),
            ),
        )

        assertEquals(TaskRunStatus.TIMED_OUT, result.taskRun.status)
        assertEquals(
            listOf(StepRunStatus.FAILED, StepRunStatus.SUCCESS),
            result.stepRuns.map(StepRunRecord::status),
        )
        assertEquals("screenshot", result.taskRun.artifactType())
        assertEquals(expectedRunArtifactPath(runId = "run-004d"), result.taskRun.artifactRelativePath())
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
                "captureScreenshot:smoke-task:run-005:step-tap-login:1",
                "stop:com.example.target",
            ),
            facade.operations,
        )
    }

    @Test
    fun shouldRecordPolicyDisabledArtifactWhenStepFailureScreenshotIsDisabled() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            tapResults += CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_ELEMENT_NOT_FOUND,
                message = "login button missing",
            )
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            runIdFactory = { "run-006" },
        )

        val result = runner.run(
            task = sampleTask(
                diagnostics = DiagnosticsPolicy(captureScreenshotOnStepFailure = false),
                steps = listOf(tapElementStep()),
            ),
        )

        assertEquals(
            "DIAG_SCREENSHOT_CAPTURE_DISABLED_BY_POLICY",
            result.stepRuns.single().artifactReason(),
        )
    }

    @Test
    fun shouldRecordStorageLimitArtifactWhenStepFailureCaptureGateDenies() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            tapResults += CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_ELEMENT_NOT_FOUND,
                message = "login button missing",
            )
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            diagnosticsArtifactCaptureGate = denyAllDiagnosticsArtifactCaptureGate,
            runIdFactory = { "run-006b" },
        )

        val result = runner.run(
            task = sampleTask(
                steps = listOf(tapElementStep()),
            ),
        )

        assertEquals(
            "DIAG_ARTIFACT_STORAGE_LIMIT_REACHED",
            result.stepRuns.single().artifactReason(),
        )
    }

    @Test
    fun shouldPersistScreenshotArtifactWhenStepFailsAndCaptureIsAllowed() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            tapResults += CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_ELEMENT_NOT_FOUND,
                message = "login button missing",
            )
            screenshotResults += CapabilityResult.Success(
                ScreenshotCapture(
                    relativePath = expectedStepArtifactPath(runId = "run-006c", stepId = "step-tap-login"),
                    mimeType = "image/png",
                    fileSizeBytes = 4096L,
                ),
            )
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            runIdFactory = { "run-006c" },
        )

        val result = runner.run(
            task = sampleTask(
                steps = listOf(tapElementStep()),
            ),
        )

        assertEquals("screenshot", result.stepRuns.single().artifactType())
        assertEquals(expectedStepArtifactPath(runId = "run-006c", stepId = "step-tap-login"), result.stepRuns.single().artifactRelativePath())
        assertEquals(4096L, result.stepRuns.single().artifactFileSizeBytes())
    }

    @Test
    fun shouldCreateDistinctScreenshotPathsAcrossTaskRetriesForSameStepFailure() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            tapResults += CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_ELEMENT_NOT_FOUND,
                message = "not found-attempt-1",
            )
            tapResults += CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_ELEMENT_NOT_FOUND,
                message = "not found-attempt-2",
            )
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            runIdFactory = { "run-retry-collision" },
        )

        val result = runner.run(
            task = sampleTask(
                executionPolicy = defaultExecutionPolicy(maxRetries = 1, retryBackoffMs = 0L),
                steps = listOf(tapElementStep()),
            ),
        )

        assertEquals(
            listOf(
                expectedStepArtifactPath(runId = "run-retry-collision", stepId = "step-tap-login", taskAttempt = 1),
                expectedStepArtifactPath(runId = "run-retry-collision", stepId = "step-tap-login", taskAttempt = 2),
            ),
            result.stepRuns.mapNotNull { it.artifactRelativePath() },
        )
    }

    @Test
    fun shouldSuppressScreenshotArtifactWhenFailureOccursAfterSensitiveInput() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            tapResults += CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_ELEMENT_NOT_FOUND,
                message = "submit button missing",
            )
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            runIdFactory = { "run-007" },
        )

        val result = runner.run(
            task = sampleTask(
                variables = buildJsonObject {
                    putJsonObject("secretPhone") {
                        put("value", "13800138000")
                        put("sensitive", true)
                    }
                },
                steps = listOf(
                    inputTextVariableRefStep("secretPhone"),
                    tapElementStep(),
                ),
            ),
        )

        assertEquals(
            "DIAG_SCREENSHOT_SUPPRESSED_FOR_SENSITIVE_CONTENT",
            result.stepRuns.last().artifactReason(),
        )
        assertEquals(true, result.stepRuns.last().artifactSensitiveContextActive())
    }

    @Test
    fun shouldSuppressScreenshotArtifactWhenSensitiveInputStepFails() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            inputResults += CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_EXECUTION_FAILED,
                message = "keyboard dismissed",
            )
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            runIdFactory = { "run-008" },
        )

        val result = runner.run(
            task = sampleTask(
                variables = buildJsonObject {
                    putJsonObject("secretPhone") {
                        put("value", "13800138000")
                        put("sensitive", true)
                    }
                },
                steps = listOf(inputTextVariableRefStep("secretPhone")),
            ),
        )

        assertEquals(TaskRunStatus.FAILED, result.taskRun.status)
        assertEquals(
            "DIAG_SCREENSHOT_SUPPRESSED_FOR_SENSITIVE_CONTENT",
            result.stepRuns.single().artifactReason(),
        )
        assertEquals(true, result.stepRuns.single().artifactSensitiveContextActive())
    }

    @Test
    fun shouldPreferSensitiveSuppressionOverStorageLimitGate() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            inputResults += CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_EXECUTION_FAILED,
                message = "keyboard dismissed",
            )
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            diagnosticsArtifactCaptureGate = denyAllDiagnosticsArtifactCaptureGate,
            runIdFactory = { "run-008b" },
        )

        val result = runner.run(
            task = sampleTask(
                variables = buildJsonObject {
                    putJsonObject("secretPhone") {
                        put("value", "13800138000")
                        put("sensitive", true)
                    }
                },
                steps = listOf(inputTextVariableRefStep("secretPhone")),
            ),
        )

        assertEquals(
            "DIAG_SCREENSHOT_SUPPRESSED_FOR_SENSITIVE_CONTENT",
            result.stepRuns.single().artifactReason(),
        )
    }

    @Test
    fun shouldClearSensitiveContextAfterStopAppBeforeLaterFailure() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            tapResults += CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_ELEMENT_NOT_FOUND,
                message = "login button missing",
            )
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            runIdFactory = { "run-008" },
        )

        val result = runner.run(
            task = sampleTask(
                variables = buildJsonObject {
                    putJsonObject("secretPhone") {
                        put("value", "13800138000")
                        put("sensitive", true)
                    }
                },
                steps = listOf(
                    inputTextVariableRefStep("secretPhone"),
                    stopAppStep(),
                    tapElementStep(),
                ),
            ),
        )

        assertEquals("screenshot", result.stepRuns.last().artifactType())
        assertEquals(expectedStepArtifactPath(runId = "run-008", stepId = "step-tap-login"), result.stepRuns.last().artifactRelativePath())
        assertEquals(false, result.stepRuns.last().artifactSensitiveContextActive())
    }

    @Test
    fun shouldClearSensitiveContextAfterClearingStepBeforeLaterFailure() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            tapResults += CapabilityResult.Success(Unit)
            tapResults += CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_ELEMENT_NOT_FOUND,
                message = "login button missing",
            )
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            runIdFactory = { "run-010" },
        )

        val result = runner.run(
            task = sampleTask(
                variables = buildJsonObject {
                    putJsonObject("secretPhone") {
                        put("value", "13800138000")
                        put("sensitive", true)
                    }
                },
                steps = listOf(
                    inputTextVariableRefStep("secretPhone"),
                    tapElementStep().copy(id = "step-clear", clearsSensitiveContext = true),
                    tapElementStep().copy(id = "step-after-clear"),
                ),
            ),
        )

        assertEquals("screenshot", result.stepRuns.last().artifactType())
        assertEquals(expectedStepArtifactPath(runId = "run-010", stepId = "step-after-clear"), result.stepRuns.last().artifactRelativePath())
        assertEquals(false, result.stepRuns.last().artifactSensitiveContextActive())
    }

    @Test
    fun shouldRecordRunLevelStorageLimitArtifactWhenTaskTimesOutBeforeStepResultIsPersistedAndGateDenies() = runBlocking {
        val facade = RecordingCapabilityFacade().apply {
            waitDelayMs = 50
        }
        val runner = DefaultTaskRunner(
            capabilityFacade = facade,
            timeSource = FakeRunnerTimeSource(),
            diagnosticsArtifactCaptureGate = denyAllDiagnosticsArtifactCaptureGate,
            runIdFactory = { "run-010b" },
        )

        val result = runner.run(
            task = sampleTask(
                executionPolicy = defaultExecutionPolicy(taskTimeoutMs = 10),
                steps = listOf(waitElementStep(timeoutMs = 5_000)),
            ),
        )

        assertEquals(TaskRunStatus.TIMED_OUT, result.taskRun.status)
        assertEquals(emptyList<StepRunRecord>(), result.stepRuns)
        assertEquals(
            "DIAG_ARTIFACT_STORAGE_LIMIT_REACHED",
            result.taskRun.artifactReason(),
        )
    }

    private fun sampleTask(
        executionPolicy: ExecutionPolicy = defaultExecutionPolicy(),
        diagnostics: DiagnosticsPolicy = DiagnosticsPolicy(),
        variables: kotlinx.serialization.json.JsonObject? = null,
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
        variables = variables,
        steps = steps,
        diagnostics = diagnostics,
        tags = emptyList(),
    )

    private fun defaultExecutionPolicy(
        taskTimeoutMs: Long = 60_000,
        maxRetries: Int = 0,
        retryBackoffMs: Long = 1_000,
    ): ExecutionPolicy = ExecutionPolicy(
        taskTimeoutMs = taskTimeoutMs,
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

    private fun inputTextVariableRefStep(textRef: String): TaskStep = TaskStep(
        id = "step-input-ref",
        type = StepType.INPUT_TEXT,
        name = null,
        timeoutMs = 5_000,
        retry = StepRetryPolicy(),
        onFailure = StepFailurePolicy.STOP_TASK,
        params = buildJsonObject {
            put("textRef", textRef)
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
        val inputResults = ArrayDeque<CapabilityResult<InputTextSummary>>()
        val screenshotResults = ArrayDeque<CapabilityResult<ScreenshotCapture>>()
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
            return if (inputResults.isEmpty()) {
                CapabilityResult.Success(
                    InputTextSummary(
                        selectorSummary = request.selector?.value,
                        source = request.source,
                        masked = request.masked,
                    ),
                )
            } else {
                inputResults.removeFirst()
            }
        }

        override suspend fun captureScreenshot(request: ScreenshotCaptureRequest): CapabilityResult<ScreenshotCapture> {
            operations += "captureScreenshot:${request.taskId}:${request.runId}:${request.stepId ?: "run"}:${request.attempt ?: 0}"
            return if (screenshotResults.isEmpty()) {
                CapabilityResult.Success(
                    ScreenshotCapture(
                        relativePath = buildArtifactRelativePath(request),
                        mimeType = "image/png",
                        fileSizeBytes = 2048L,
                    ),
                )
            } else {
                screenshotResults.removeFirst()
            }
        }

        private fun buildArtifactRelativePath(request: ScreenshotCaptureRequest): String {
            val taskAttempt = request.taskAttempt ?: 1
            return if (request.stepId == null) {
                buildString {
                    append("diagnostics/screenshots/")
                    append(request.taskId)
                    append('/')
                    append(request.runId)
                    append('/')
                    append("run")
                    if (taskAttempt > 1) {
                        append("-task")
                        append(taskAttempt)
                    }
                    append(".png")
                }
            } else {
                val stepId = request.stepId ?: error("stepId must be present for step artifact path")
                val sanitizedStepId = stepId.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "artifact" }
                val stepHash = stepId.hashCode().toUInt().toString(16)
                buildString {
                    append("diagnostics/screenshots/")
                    append(request.taskId)
                    append('/')
                    append(request.runId)
                    append('/')
                    append(sanitizedStepId)
                    append('-')
                    append(stepHash)
                    if (taskAttempt > 1) {
                        append("-task")
                        append(taskAttempt)
                    }
                    append("-attempt")
                    append(request.attempt ?: 1)
                    append(".png")
                }
            }
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

    private val denyAllDiagnosticsArtifactCaptureGate = object : DiagnosticsArtifactCaptureGate {
        override suspend fun canCaptureFailureArtifact(taskId: String?, runId: String?): Boolean = false
    }

    private fun StepRunRecord.artifactReason(): String? = Json.parseToJsonElement(artifactsJson)
        .jsonObject["reason"]
        ?.jsonPrimitive
        ?.content

    private fun StepRunRecord.artifactType(): String? = Json.parseToJsonElement(artifactsJson)
        .jsonObject["artifactType"]
        ?.jsonPrimitive
        ?.content

    private fun StepRunRecord.artifactRelativePath(): String? = Json.parseToJsonElement(artifactsJson)
        .jsonObject["relativePath"]
        ?.jsonPrimitive
        ?.content

    private fun StepRunRecord.artifactFileSizeBytes(): Long? = Json.parseToJsonElement(artifactsJson)
        .jsonObject["fileSizeBytes"]
        ?.jsonPrimitive
        ?.content
        ?.toLongOrNull()

    private fun StepRunRecord.artifactSensitiveContextActive(): Boolean? = Json.parseToJsonElement(artifactsJson)
        .jsonObject["sensitiveContextActive"]
        ?.jsonPrimitive
        ?.content
        ?.toBooleanStrictOrNull()

    private fun com.plearn.appcontrol.data.model.TaskRunRecord.artifactReason(): String? = Json.parseToJsonElement(artifactsJson)
        .jsonObject["reason"]
        ?.jsonPrimitive
        ?.content

    private fun com.plearn.appcontrol.data.model.TaskRunRecord.artifactType(): String? = Json.parseToJsonElement(artifactsJson)
        .jsonObject["artifactType"]
        ?.jsonPrimitive
        ?.content

    private fun com.plearn.appcontrol.data.model.TaskRunRecord.artifactRelativePath(): String? = Json.parseToJsonElement(artifactsJson)
        .jsonObject["relativePath"]
        ?.jsonPrimitive
        ?.content

    private fun expectedRunArtifactPath(taskId: String = "smoke-task", runId: String, taskAttempt: Int = 1): String =
        buildString {
            append("diagnostics/screenshots/")
            append(taskId)
            append('/')
            append(runId)
            append('/')
            append("run")
            if (taskAttempt > 1) {
                append("-task")
                append(taskAttempt)
            }
            append(".png")
        }

    private fun expectedStepArtifactPath(
        taskId: String = "smoke-task",
        runId: String,
        stepId: String,
        taskAttempt: Int = 1,
        stepAttempt: Int = 1,
    ): String {
        val sanitizedStepId = stepId.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "artifact" }
        val stepHash = stepId.hashCode().toUInt().toString(16)
        return buildString {
            append("diagnostics/screenshots/")
            append(taskId)
            append('/')
            append(runId)
            append('/')
            append(sanitizedStepId)
            append('-')
            append(stepHash)
            if (taskAttempt > 1) {
                append("-task")
                append(taskAttempt)
            }
            append("-attempt")
            append(stepAttempt)
            append(".png")
        }
    }
}
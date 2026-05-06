package com.plearn.appcontrol.runner

import com.plearn.appcontrol.capability.CapabilityFacade
import com.plearn.appcontrol.capability.CapabilityFailureCode
import com.plearn.appcontrol.capability.CapabilityResult
import com.plearn.appcontrol.capability.ElementSelector
import com.plearn.appcontrol.capability.InputTextRequest
import com.plearn.appcontrol.capability.InputTextSource
import com.plearn.appcontrol.capability.ScreenPoint
import com.plearn.appcontrol.capability.SelectorType
import com.plearn.appcontrol.capability.SwipeRequest
import com.plearn.appcontrol.capability.TapTarget
import com.plearn.appcontrol.capability.WaitElementState
import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.dsl.StepFailurePolicy
import com.plearn.appcontrol.dsl.StepType
import com.plearn.appcontrol.dsl.TaskDefinition
import com.plearn.appcontrol.dsl.TaskStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.util.UUID

class DefaultTaskRunner(
    private val capabilityFacade: CapabilityFacade,
    private val timeSource: RunnerTimeSource = SystemRunnerTimeSource,
    private val runIdFactory: () -> String = { UUID.randomUUID().toString() },
) : TaskRunner {
    override suspend fun run(task: TaskDefinition, triggerType: String): TaskExecutionResult {
        val runId = runIdFactory()
        val runStartedAt = timeSource.nowMs()
        val stepRuns = mutableListOf<StepRunRecord>()
        var taskAttemptCount = 0

        return try {
            val terminalOutcome = withTimeoutOrNull(task.executionPolicy.taskTimeoutMs) {
                executeTask(task = task, runId = runId, stepRuns = stepRuns).also {
                    taskAttemptCount = it.attemptCount
                }
            } ?: TaskTerminalOutcome(
                status = TaskRunStatus.TIMED_OUT,
                errorCode = CapabilityFailureCode.STEP_TIMEOUT,
                message = "Task execution timed out.",
                attemptCount = taskAttemptCount.coerceAtLeast(1),
            )

            buildResult(
                runId = runId,
                task = task,
                triggerType = triggerType,
                runStartedAt = runStartedAt,
                stepRuns = stepRuns,
                terminalOutcome = terminalOutcome,
            )
        } catch (_: CancellationException) {
            buildResult(
                runId = runId,
                task = task,
                triggerType = triggerType,
                runStartedAt = runStartedAt,
                stepRuns = stepRuns,
                terminalOutcome = TaskTerminalOutcome(
                    status = TaskRunStatus.CANCELLED,
                    errorCode = RunnerFailureCode.RUNNER_TASK_CANCELLED,
                    message = "Task execution was cancelled.",
                    attemptCount = taskAttemptCount.coerceAtLeast(1),
                ),
            )
        }
    }

    private suspend fun executeTask(
        task: TaskDefinition,
        runId: String,
        stepRuns: MutableList<StepRunRecord>,
    ): TaskTerminalOutcome {
        val maxAttempts = task.executionPolicy.maxRetries + 1
        var lastOutcome = TaskTerminalOutcome(
            status = TaskRunStatus.FAILED,
            errorCode = null,
            message = null,
            attemptCount = 1,
        )

        for (attempt in 1..maxAttempts) {
            val attemptOutcome = executeAttempt(task = task, runId = runId, stepRuns = stepRuns)
            if (attemptOutcome.success) {
                return TaskTerminalOutcome(
                    status = TaskRunStatus.SUCCESS,
                    errorCode = null,
                    message = null,
                    attemptCount = attempt,
                )
            }

            lastOutcome = TaskTerminalOutcome(
                status = attemptOutcome.status,
                errorCode = attemptOutcome.errorCode,
                message = attemptOutcome.message,
                attemptCount = attempt,
            )

            if (!attemptOutcome.retryTask || attempt >= maxAttempts) {
                return lastOutcome
            }

            if (task.executionPolicy.retryBackoffMs > 0L) {
                timeSource.delay(task.executionPolicy.retryBackoffMs)
            }
        }

        return lastOutcome
    }

    private suspend fun executeAttempt(
        task: TaskDefinition,
        runId: String,
        stepRuns: MutableList<StepRunRecord>,
    ): AttemptOutcome {
        for (step in task.steps) {
            when (val stepOutcome = executeStepWithRetries(task = task, step = step, runId = runId, stepRuns = stepRuns)) {
                is StepOutcome.ContinueSuccess -> Unit
                is StepOutcome.ContinueAfterFailure -> Unit
                is StepOutcome.FailTask -> return AttemptOutcome(
                    success = false,
                    status = stepOutcome.status,
                    errorCode = stepOutcome.errorCode,
                    message = stepOutcome.message,
                    retryTask = stepOutcome.retryTask,
                )
            }
        }

        return AttemptOutcome(
            success = true,
            status = TaskRunStatus.SUCCESS,
            errorCode = null,
            message = null,
            retryTask = false,
        )
    }

    private suspend fun executeStepWithRetries(
        task: TaskDefinition,
        step: TaskStep,
        runId: String,
        stepRuns: MutableList<StepRunRecord>,
    ): StepOutcome {
        val maxAttempts = step.retry.maxRetries + 1

        for (attempt in 1..maxAttempts) {
            val stepStartedAt = timeSource.nowMs()
            val execution = withTimeoutOrNull(step.timeoutMs) {
                executeStep(task = task, step = step)
            } ?: StepExecutionFailure(
                errorCode = CapabilityFailureCode.STEP_TIMEOUT,
                message = "Step ${step.id} timed out.",
                blockTask = false,
                taskStatus = TaskRunStatus.TIMED_OUT,
                stepStatus = StepRunStatus.TIMED_OUT,
            )
            val stepFinishedAt = timeSource.nowMs()

            when (execution) {
                is StepExecutionSuccess -> {
                    stepRuns += StepRunRecord(
                        runId = runId,
                        stepId = step.id,
                        status = StepRunStatus.SUCCESS,
                        startedAt = stepStartedAt,
                        finishedAt = stepFinishedAt,
                        durationMs = stepFinishedAt - stepStartedAt,
                        errorCode = null,
                        message = null,
                        artifactsJson = "{}",
                    )
                    return StepOutcome.ContinueSuccess
                }

                is StepExecutionFailure -> {
                    stepRuns += StepRunRecord(
                        runId = runId,
                        stepId = step.id,
                        status = execution.stepStatus,
                        startedAt = stepStartedAt,
                        finishedAt = stepFinishedAt,
                        durationMs = stepFinishedAt - stepStartedAt,
                        errorCode = execution.errorCode,
                        message = execution.message,
                        artifactsJson = "{}",
                    )

                    if (attempt < maxAttempts) {
                        if (step.retry.backoffMs > 0L) {
                            timeSource.delay(step.retry.backoffMs)
                        }
                        continue
                    }

                    if (execution.blockTask) {
                        return StepOutcome.FailTask(
                            status = execution.taskStatus,
                            errorCode = execution.errorCode,
                            message = execution.message,
                            retryTask = false,
                        )
                    }

                    if (step.onFailure == StepFailurePolicy.CONTINUE) {
                        return StepOutcome.ContinueAfterFailure
                    }

                    return StepOutcome.FailTask(
                        status = execution.taskStatus,
                        errorCode = execution.errorCode,
                        message = execution.message,
                        retryTask = true,
                    )
                }
            }
        }

        return StepOutcome.FailTask(
            status = TaskRunStatus.FAILED,
            errorCode = CapabilityFailureCode.STEP_EXECUTION_FAILED,
            message = "Step ${step.id} failed.",
            retryTask = false,
        )
    }

    private suspend fun executeStep(task: TaskDefinition, step: TaskStep): StepExecutionTerminal {
        return when (step.type) {
            StepType.START_APP -> {
                val packageName = step.params.stringValue("packageName") ?: task.targetApp.packageName
                mapUnitResult(capabilityFacade.startApp(packageName))
            }

            StepType.STOP_APP -> {
                val packageName = step.params.stringValue("packageName") ?: task.targetApp.packageName
                mapUnitResult(capabilityFacade.stopApp(packageName))
            }

            StepType.RESTART_APP -> {
                val packageName = step.params.stringValue("packageName") ?: task.targetApp.packageName
                val waitAfterStopMs = step.params.longValue("waitAfterStopMs") ?: 0L
                mapUnitResult(capabilityFacade.restartApp(packageName, waitAfterStopMs))
            }

            StepType.TAP -> {
                val tapTarget = parseTapTarget(step.params)
                    ?: return StepExecutionFailure(
                        errorCode = RunnerFailureCode.RUNNER_INVALID_STEP_ARGUMENT,
                        message = "Step ${step.id} is missing a supported tap target.",
                        blockTask = true,
                        taskStatus = TaskRunStatus.BLOCKED,
                    )
                mapUnitResult(capabilityFacade.tap(tapTarget))
            }

            StepType.SWIPE -> {
                val request = parseSwipeRequest(step.params)
                    ?: return StepExecutionFailure(
                        errorCode = RunnerFailureCode.RUNNER_INVALID_STEP_ARGUMENT,
                        message = "Step ${step.id} is missing swipe coordinates.",
                        blockTask = true,
                        taskStatus = TaskRunStatus.BLOCKED,
                    )
                mapUnitResult(capabilityFacade.swipe(request))
            }

            StepType.INPUT_TEXT -> {
                val request = parseInputTextRequest(task = task, step = step)
                when (request) {
                    is InputTextParseResult.Success -> mapInputResult(capabilityFacade.inputText(request.request))
                    is InputTextParseResult.Failure -> StepExecutionFailure(
                        errorCode = request.errorCode,
                        message = request.message,
                        blockTask = true,
                        taskStatus = TaskRunStatus.BLOCKED,
                    )
                }
            }

            StepType.WAIT_ELEMENT -> {
                val selector = step.params.objectValue("selector")?.toSelector()
                    ?: return StepExecutionFailure(
                        errorCode = RunnerFailureCode.RUNNER_INVALID_STEP_ARGUMENT,
                        message = "Step ${step.id} is missing selector.",
                        blockTask = true,
                        taskStatus = TaskRunStatus.BLOCKED,
                    )
                val state = parseWaitState(step.params.stringValue("state"))
                    ?: return StepExecutionFailure(
                        errorCode = RunnerFailureCode.RUNNER_INVALID_STEP_ARGUMENT,
                        message = "Step ${step.id} has unsupported wait state.",
                        blockTask = true,
                        taskStatus = TaskRunStatus.BLOCKED,
                    )
                mapUnitResult(capabilityFacade.waitForElement(selector, state, step.timeoutMs))
            }
        }
    }

    private fun mapUnitResult(result: CapabilityResult<Unit>): StepExecutionTerminal =
        when (result) {
            is CapabilityResult.Success -> StepExecutionSuccess
            is CapabilityResult.Failure -> result.toStepFailure()
        }

    private fun mapInputResult(result: CapabilityResult<*>): StepExecutionTerminal =
        when (result) {
            is CapabilityResult.Success -> StepExecutionSuccess
            is CapabilityResult.Failure -> result.toStepFailure()
        }

    private fun CapabilityResult.Failure.toStepFailure(): StepExecutionFailure = StepExecutionFailure(
        errorCode = errorCode,
        message = message,
        blockTask = errorCode == CapabilityFailureCode.STEP_CAPABILITY_UNAVAILABLE,
        taskStatus = when (errorCode) {
            CapabilityFailureCode.STEP_TIMEOUT -> TaskRunStatus.TIMED_OUT
            CapabilityFailureCode.STEP_CAPABILITY_UNAVAILABLE -> TaskRunStatus.BLOCKED
            else -> TaskRunStatus.FAILED
        },
        stepStatus = if (errorCode == CapabilityFailureCode.STEP_TIMEOUT) StepRunStatus.TIMED_OUT else StepRunStatus.FAILED,
    )

    private fun parseTapTarget(params: JsonObject): TapTarget? {
        val target = params.objectValue("target") ?: return null
        return when (target.stringValue("kind")?.lowercase()) {
            "element" -> target.objectValue("selector")?.toSelector()?.let(TapTarget::Element)
            "coordinate" -> {
                val point = target.objectValue("point") ?: return null
                val x = point.intValue("x") ?: return null
                val y = point.intValue("y") ?: return null
                TapTarget.Coordinate(ScreenPoint(x = x, y = y))
            }

            "ocr_text" -> target.stringValue("text")?.let(TapTarget::OcrText)
            "image" -> target.stringValue("templateId")?.let(TapTarget::Image)
            else -> null
        }
    }

    private fun parseSwipeRequest(params: JsonObject): SwipeRequest? {
        val from = params.objectValue("from") ?: return null
        val to = params.objectValue("to") ?: return null
        val fromX = from.intValue("x") ?: return null
        val fromY = from.intValue("y") ?: return null
        val toX = to.intValue("x") ?: return null
        val toY = to.intValue("y") ?: return null
        val durationMs = params.longValue("durationMs") ?: return null
        return SwipeRequest(
            from = ScreenPoint(x = fromX, y = fromY),
            to = ScreenPoint(x = toX, y = toY),
            durationMs = durationMs,
        )
    }

    private fun parseInputTextRequest(task: TaskDefinition, step: TaskStep): InputTextParseResult {
        val selector = step.params.objectValue("selector")?.toSelector()
        val text = step.params.stringValue("text")
        val textRef = step.params.stringValue("textRef")

        if ((text == null && textRef == null) || (text != null && textRef != null)) {
            return InputTextParseResult.Failure(
                errorCode = RunnerFailureCode.RUNNER_INVALID_STEP_ARGUMENT,
                message = "Step ${step.id} must provide exactly one of text or textRef.",
            )
        }

        if (text != null) {
            return InputTextParseResult.Success(
                request = InputTextRequest(
                    text = text,
                    selector = selector,
                    clearBeforeInput = step.params.booleanValue("clearBeforeInput") ?: false,
                    source = InputTextSource.LITERAL,
                    masked = false,
                ),
            )
        }

        val resolvedText = resolveTextReference(task, textRef!!)
            ?: return InputTextParseResult.Failure(
                errorCode = RunnerFailureCode.RUNNER_TEXT_REFERENCE_UNRESOLVED,
                message = "textRef $textRef could not be resolved.",
            )
        return InputTextParseResult.Success(
            request = InputTextRequest(
                text = resolvedText.value,
                selector = selector,
                clearBeforeInput = step.params.booleanValue("clearBeforeInput") ?: false,
                source = InputTextSource.VARIABLE_REFERENCE,
                masked = resolvedText.masked,
            ),
        )
    }

    private fun resolveTextReference(task: TaskDefinition, textRef: String): ResolvedText? {
        val variable = task.variables?.get(textRef) ?: return null
        return when (variable) {
            is JsonPrimitive -> variable.contentOrNull?.let { ResolvedText(value = it, masked = false) }
            is JsonObject -> {
                val value = (variable["value"] as? JsonPrimitive)?.contentOrNull ?: return null
                val masked = (variable["sensitive"] as? JsonPrimitive)?.booleanOrNull ?: false
                ResolvedText(value = value, masked = masked)
            }

            else -> null
        }
    }

    private fun parseWaitState(rawState: String?): WaitElementState? = when (rawState?.lowercase()) {
        "visible", "appeared" -> WaitElementState.APPEARED
        "hidden", "disappeared", "gone" -> WaitElementState.DISAPPEARED
        else -> null
    }

    private fun JsonObject.toSelector(): ElementSelector? {
        val by = when (stringValue("by")?.lowercase()) {
            "resourceid" -> SelectorType.RESOURCE_ID
            "text" -> SelectorType.TEXT
            "contentdescription" -> SelectorType.CONTENT_DESCRIPTION
            "classname" -> SelectorType.CLASS_NAME
            else -> null
        } ?: return null
        val value = stringValue("value") ?: return null
        return ElementSelector(by = by, value = value)
    }

    private fun JsonObject.objectValue(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.stringValue(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.booleanValue(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.longValue(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    private fun JsonObject.intValue(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun buildResult(
        runId: String,
        task: TaskDefinition,
        triggerType: String,
        runStartedAt: Long,
        stepRuns: List<StepRunRecord>,
        terminalOutcome: TaskTerminalOutcome,
    ): TaskExecutionResult {
        val finishedAt = timeSource.nowMs()
        return TaskExecutionResult(
            taskRun = TaskRunRecord(
                runId = runId,
                sessionId = null,
                cycleNo = null,
                taskId = task.taskId,
                credentialSetId = null,
                credentialProfileId = null,
                credentialAlias = null,
                status = terminalOutcome.status,
                startedAt = runStartedAt,
                finishedAt = finishedAt,
                durationMs = finishedAt - runStartedAt,
                triggerType = triggerType,
                errorCode = terminalOutcome.errorCode,
                message = terminalOutcome.message,
            ),
            stepRuns = stepRuns.toList(),
            taskAttemptCount = terminalOutcome.attemptCount,
        )
    }

    private data class AttemptOutcome(
        val success: Boolean,
        val status: String,
        val errorCode: String?,
        val message: String?,
        val retryTask: Boolean,
    )

    private data class TaskTerminalOutcome(
        val status: String,
        val errorCode: String?,
        val message: String?,
        val attemptCount: Int,
    )

    private sealed interface StepExecutionTerminal

    private data object StepExecutionSuccess : StepExecutionTerminal

    private data class StepExecutionFailure(
        val errorCode: String,
        val message: String,
        val blockTask: Boolean,
        val taskStatus: String,
        val stepStatus: String = StepRunStatus.FAILED,
    ) : StepExecutionTerminal

    private sealed interface StepOutcome {
        data object ContinueSuccess : StepOutcome
        data object ContinueAfterFailure : StepOutcome

        data class FailTask(
            val status: String,
            val errorCode: String,
            val message: String,
            val retryTask: Boolean,
        ) : StepOutcome
    }

    private sealed interface InputTextParseResult {
        data class Success(val request: InputTextRequest) : InputTextParseResult

        data class Failure(
            val errorCode: String,
            val message: String,
        ) : InputTextParseResult
    }

    private data class ResolvedText(
        val value: String,
        val masked: Boolean,
    )
}
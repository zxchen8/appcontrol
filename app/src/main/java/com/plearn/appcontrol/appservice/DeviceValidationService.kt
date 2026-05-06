package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.capability.ElementSelector
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
import com.plearn.appcontrol.runner.RunTriggerType
import com.plearn.appcontrol.runner.TaskExecutionResult
import com.plearn.appcontrol.runner.TaskRunner
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

data class TapSmokeCheckRequest(
    val packageName: String,
    val selector: ElementSelector,
)

data class DeviceValidationResult(
    val environment: DeviceEnvironmentReport,
    val execution: TaskExecutionResult?,
    val errorCode: String? = null,
    val message: String? = null,
)

object DeviceValidationErrorCode {
    const val ROOT_NOT_READY = "ROOT_NOT_READY"
    const val ACCESSIBILITY_NOT_ENABLED = "ACCESSIBILITY_NOT_ENABLED"
    const val ACCESSIBILITY_NOT_CONNECTED = "ACCESSIBILITY_NOT_CONNECTED"
}

@Singleton
class DeviceValidationService @Inject constructor(
    private val environmentInspector: DeviceEnvironmentInspector,
    private val taskRunner: TaskRunner,
) {
    suspend fun inspectEnvironment(): DeviceEnvironmentReport = environmentInspector.inspect()

    suspend fun runTapSmokeCheck(request: TapSmokeCheckRequest): DeviceValidationResult {
        val environment = environmentInspector.inspect()
        if (!environment.rootReady) {
            return DeviceValidationResult(
                environment = environment,
                execution = null,
                errorCode = DeviceValidationErrorCode.ROOT_NOT_READY,
                message = "Root shell is not available.",
            )
        }
        if (!environment.accessibilityEnabled) {
            return DeviceValidationResult(
                environment = environment,
                execution = null,
                errorCode = DeviceValidationErrorCode.ACCESSIBILITY_NOT_ENABLED,
                message = "Accessibility service is not enabled.",
            )
        }
        if (!environment.accessibilityConnected) {
            return DeviceValidationResult(
                environment = environment,
                execution = null,
                errorCode = DeviceValidationErrorCode.ACCESSIBILITY_NOT_CONNECTED,
                message = "Accessibility service is not connected.",
            )
        }

        return DeviceValidationResult(
            environment = environment,
            execution = taskRunner.run(
                task = buildSmokeTask(request),
                triggerType = RunTriggerType.MANUAL,
            ),
        )
    }

    private fun buildSmokeTask(request: TapSmokeCheckRequest): TaskDefinition = TaskDefinition(
        schemaVersion = "1.0",
        taskId = "device-validation-smoke",
        name = "Device Validation Smoke",
        description = "Validate accessibility binding and minimal tap chain.",
        enabled = true,
        targetApp = TargetApp(packageName = request.packageName),
        trigger = TaskTrigger.Cron(expression = "*/5 * * * *", timezone = "Asia/Shanghai"),
        accountRotation = null,
        executionPolicy = ExecutionPolicy(
            taskTimeoutMs = 30_000,
            maxRetries = 0,
            retryBackoffMs = 1_000,
            conflictPolicy = ConflictPolicy.SKIP,
            onMissedSchedule = MissedSchedulePolicy.SKIP,
        ),
        preconditions = emptyList(),
        variables = null,
        steps = listOf(
            TaskStep(
                id = "smoke-start-app",
                type = StepType.START_APP,
                name = null,
                timeoutMs = 10_000,
                retry = StepRetryPolicy(),
                onFailure = StepFailurePolicy.STOP_TASK,
                params = buildJsonObject {
                    put("packageName", request.packageName)
                },
            ),
            TaskStep(
                id = "smoke-wait-element",
                type = StepType.WAIT_ELEMENT,
                name = null,
                timeoutMs = 10_000,
                retry = StepRetryPolicy(),
                onFailure = StepFailurePolicy.STOP_TASK,
                params = buildJsonObject {
                    put("state", "visible")
                    putJsonObject("selector") {
                        put("by", request.selector.by.toDslValue())
                        put("value", request.selector.value)
                    }
                },
            ),
            TaskStep(
                id = "smoke-tap-element",
                type = StepType.TAP,
                name = null,
                timeoutMs = 10_000,
                retry = StepRetryPolicy(maxRetries = 1, backoffMs = 1_000),
                onFailure = StepFailurePolicy.STOP_TASK,
                params = buildJsonObject {
                    putJsonObject("target") {
                        put("kind", "element")
                        putJsonObject("selector") {
                            put("by", request.selector.by.toDslValue())
                            put("value", request.selector.value)
                        }
                    }
                },
            ),
        ),
        diagnostics = DiagnosticsPolicy(),
        tags = listOf("validation", "smoke"),
    )

    private fun com.plearn.appcontrol.capability.SelectorType.toDslValue(): String = when (this) {
        com.plearn.appcontrol.capability.SelectorType.RESOURCE_ID -> "resourceId"
        com.plearn.appcontrol.capability.SelectorType.TEXT -> "text"
        com.plearn.appcontrol.capability.SelectorType.CONTENT_DESCRIPTION -> "contentDescription"
        com.plearn.appcontrol.capability.SelectorType.CLASS_NAME -> "className"
    }
}
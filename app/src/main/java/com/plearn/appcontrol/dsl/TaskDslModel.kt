package com.plearn.appcontrol.dsl

import kotlinx.serialization.json.JsonObject

enum class DefinitionStatus {
    READY,
    INVALID,
}

enum class DslValidationCode {
    INVALID_JSON,
    MISSING_REQUIRED_FIELD,
    UNSUPPORTED_SCHEMA_VERSION,
    INVALID_FORMAT,
    INVALID_ENUM,
    INVALID_VALUE,
    CONFLICTING_FIELDS,
    DUPLICATE_ID,
}

data class DslValidationError(
    val path: String,
    val code: DslValidationCode,
    val message: String,
)

data class TaskDefinitionParseResult(
    val task: TaskDefinition? = null,
    val errors: List<DslValidationError> = emptyList(),
) {
    val definitionStatus: DefinitionStatus
        get() = if (errors.isEmpty()) DefinitionStatus.READY else DefinitionStatus.INVALID
}

data class TaskDefinition(
    val schemaVersion: String,
    val taskId: String,
    val name: String,
    val description: String?,
    val enabled: Boolean,
    val targetApp: TargetApp,
    val trigger: TaskTrigger,
    val accountRotation: JsonObject?,
    val executionPolicy: ExecutionPolicy,
    val preconditions: List<JsonObject>,
    val variables: JsonObject?,
    val steps: List<TaskStep>,
    val diagnostics: DiagnosticsPolicy = DiagnosticsPolicy(),
    val tags: List<String> = emptyList(),
)

data class TargetApp(
    val packageName: String,
    val launchActivity: String? = null,
)

sealed interface TaskTrigger {
    data class Cron(
        val expression: String,
        val timezone: String,
    ) : TaskTrigger

    data class Continuous(
        val cooldownMs: Long,
        val maxCycles: Int?,
        val maxDurationMs: Long?,
    ) : TaskTrigger
}

enum class ConflictPolicy {
    SKIP,
    RUN_AFTER_CURRENT,
}

enum class MissedSchedulePolicy {
    SKIP,
}

data class ExecutionPolicy(
    val taskTimeoutMs: Long,
    val maxRetries: Int,
    val retryBackoffMs: Long,
    val conflictPolicy: ConflictPolicy,
    val onMissedSchedule: MissedSchedulePolicy,
)

data class DiagnosticsPolicy(
    val captureScreenshotOnFailure: Boolean = true,
    val captureScreenshotOnStepFailure: Boolean = true,
    val logLevel: String = "info",
)

enum class StepType {
    START_APP,
    STOP_APP,
    RESTART_APP,
    TAP,
    SWIPE,
    INPUT_TEXT,
    WAIT_ELEMENT,
}

enum class StepFailurePolicy {
    STOP_TASK,
    CONTINUE,
    RETRY_TASK,
}

data class StepRetryPolicy(
    val maxRetries: Int = 0,
    val backoffMs: Long = 1000,
)

data class TaskStep(
    val id: String,
    val type: StepType,
    val name: String?,
    val timeoutMs: Long,
    val retry: StepRetryPolicy = StepRetryPolicy(),
    val onFailure: StepFailurePolicy = StepFailurePolicy.STOP_TASK,
    val clearsSensitiveContext: Boolean = false,
    val params: JsonObject,
)
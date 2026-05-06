package com.plearn.appcontrol.dsl

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull

class TaskDslParser(
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    },
) {
    fun parse(rawJson: String): TaskDefinitionParseResult {
        val payload = try {
            json.decodeFromString<TaskDefinitionPayload>(rawJson)
        } catch (error: SerializationException) {
            return TaskDefinitionParseResult(
                errors = listOf(
                    DslValidationError(
                        path = "$",
                        code = DslValidationCode.INVALID_JSON,
                        message = error.message ?: "Invalid task JSON.",
                    ),
                ),
            )
        }

        val errors = mutableListOf<DslValidationError>()

        val schemaVersion = requiredString(payload.schemaVersion, "schemaVersion", errors)
        if (schemaVersion != null && schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            errors += error(
                path = "schemaVersion",
                code = DslValidationCode.UNSUPPORTED_SCHEMA_VERSION,
                message = "Only schemaVersion 1.0 is supported.",
            )
        }

        val taskId = requiredString(payload.taskId, "taskId", errors)
        if (taskId != null && !TASK_ID_REGEX.matches(taskId)) {
            errors += error(
                path = "taskId",
                code = DslValidationCode.INVALID_FORMAT,
                message = "taskId must contain only lowercase letters, numbers, and hyphens.",
            )
        }

        val name = requiredString(payload.name, "name", errors)
        val enabled = payload.enabled ?: run {
            errors += error(
                path = "enabled",
                code = DslValidationCode.MISSING_REQUIRED_FIELD,
                message = "enabled is required.",
            )
            false
        }

        val targetApp = validateTargetApp(payload.targetApp, errors)
        val trigger = validateTrigger(payload.trigger, errors)
        val executionPolicy = validateExecutionPolicy(payload.executionPolicy, errors)
        val steps = validateSteps(payload.steps, errors)
        val diagnostics = validateDiagnostics(payload.diagnostics)

        if (payload.accountRotation != null && trigger !is TaskTrigger.Continuous) {
            errors += error(
                path = "accountRotation",
                code = DslValidationCode.INVALID_VALUE,
                message = "accountRotation is only allowed for continuous trigger.",
            )
        }

        if (errors.isNotEmpty() || schemaVersion == null || taskId == null || name == null || targetApp == null || trigger == null || executionPolicy == null || steps == null) {
            return TaskDefinitionParseResult(errors = errors)
        }

        return TaskDefinitionParseResult(
            task = TaskDefinition(
                schemaVersion = schemaVersion,
                taskId = taskId,
                name = name,
                description = payload.description,
                enabled = enabled,
                targetApp = targetApp,
                trigger = trigger,
                accountRotation = payload.accountRotation,
                executionPolicy = executionPolicy,
                preconditions = payload.preconditions.orEmpty(),
                variables = payload.variables,
                steps = steps,
                diagnostics = diagnostics,
                tags = payload.tags.orEmpty(),
            ),
        )
    }

    private fun validateTargetApp(
        payload: TargetAppPayload?,
        errors: MutableList<DslValidationError>,
    ): TargetApp? {
        if (payload == null) {
            errors += error("targetApp", DslValidationCode.MISSING_REQUIRED_FIELD, "targetApp is required.")
            return null
        }

        val packageName = requiredString(payload.packageName, "targetApp.packageName", errors) ?: return null
        return TargetApp(packageName = packageName, launchActivity = payload.launchActivity)
    }

    private fun validateTrigger(
        payload: TriggerPayload?,
        errors: MutableList<DslValidationError>,
    ): TaskTrigger? {
        if (payload == null) {
            errors += error("trigger", DslValidationCode.MISSING_REQUIRED_FIELD, "trigger is required.")
            return null
        }

        return when (payload.type) {
            null -> {
                errors += error("trigger.type", DslValidationCode.MISSING_REQUIRED_FIELD, "trigger.type is required.")
                null
            }

            "cron" -> {
                val expression = requiredString(payload.expression, "trigger.expression", errors)
                val timezone = requiredString(payload.timezone, "trigger.timezone", errors)
                if (expression != null && !isFivePartCron(expression)) {
                    errors += error(
                        path = "trigger.expression",
                        code = DslValidationCode.INVALID_FORMAT,
                        message = "Cron expression must have exactly five parts.",
                    )
                }
                if (expression != null && timezone != null) {
                    TaskTrigger.Cron(expression = expression, timezone = timezone)
                } else {
                    null
                }
            }

            "continuous" -> {
                val cooldownMs = payload.cooldownMs ?: run {
                    errors += error("trigger.cooldownMs", DslValidationCode.MISSING_REQUIRED_FIELD, "cooldownMs is required for continuous trigger.")
                    null
                }
                if (cooldownMs != null && cooldownMs <= 0L) {
                    errors += error("trigger.cooldownMs", DslValidationCode.INVALID_VALUE, "cooldownMs must be greater than zero.")
                }
                if (payload.maxCycles != null && payload.maxCycles <= 0) {
                    errors += error("trigger.maxCycles", DslValidationCode.INVALID_VALUE, "maxCycles must be greater than zero.")
                }
                if (payload.maxDurationMs != null && payload.maxDurationMs <= 0L) {
                    errors += error("trigger.maxDurationMs", DslValidationCode.INVALID_VALUE, "maxDurationMs must be greater than zero.")
                }
                if (cooldownMs != null && cooldownMs > 0L) {
                    TaskTrigger.Continuous(
                        cooldownMs = cooldownMs,
                        maxCycles = payload.maxCycles,
                        maxDurationMs = payload.maxDurationMs,
                    )
                } else {
                    null
                }
            }

            "manual" -> {
                errors += error("trigger.type", DslValidationCode.INVALID_ENUM, "manual is not a supported DSL trigger type.")
                null
            }

            else -> {
                errors += error("trigger.type", DslValidationCode.INVALID_ENUM, "Unsupported trigger.type: ${payload.type}.")
                null
            }
        }
    }

    private fun validateExecutionPolicy(
        payload: ExecutionPolicyPayload?,
        errors: MutableList<DslValidationError>,
    ): ExecutionPolicy? {
        if (payload == null) {
            errors += error(
                "executionPolicy",
                DslValidationCode.MISSING_REQUIRED_FIELD,
                "executionPolicy is required.",
            )
            return null
        }

        val taskTimeoutMs = requiredLong(payload.taskTimeoutMs, "executionPolicy.taskTimeoutMs", errors)
        val maxRetries = requiredInt(payload.maxRetries, "executionPolicy.maxRetries", errors)
        val retryBackoffMs = requiredLong(payload.retryBackoffMs, "executionPolicy.retryBackoffMs", errors)
        val conflictPolicy = when (payload.conflictPolicy) {
            null -> {
                errors += error("executionPolicy.conflictPolicy", DslValidationCode.MISSING_REQUIRED_FIELD, "conflictPolicy is required.")
                null
            }

            "skip" -> ConflictPolicy.SKIP
            "run_after_current" -> ConflictPolicy.RUN_AFTER_CURRENT
            else -> {
                errors += error("executionPolicy.conflictPolicy", DslValidationCode.INVALID_ENUM, "Unsupported conflictPolicy: ${payload.conflictPolicy}.")
                null
            }
        }
        val onMissedSchedule = when (payload.onMissedSchedule) {
            null -> {
                errors += error("executionPolicy.onMissedSchedule", DslValidationCode.MISSING_REQUIRED_FIELD, "onMissedSchedule is required.")
                null
            }

            "skip" -> MissedSchedulePolicy.SKIP
            else -> {
                errors += error("executionPolicy.onMissedSchedule", DslValidationCode.INVALID_ENUM, "Unsupported onMissedSchedule: ${payload.onMissedSchedule}.")
                null
            }
        }

        if (taskTimeoutMs != null && taskTimeoutMs <= 0L) {
            errors += error("executionPolicy.taskTimeoutMs", DslValidationCode.INVALID_VALUE, "taskTimeoutMs must be greater than zero.")
        }
        if (maxRetries != null && maxRetries < 0) {
            errors += error("executionPolicy.maxRetries", DslValidationCode.INVALID_VALUE, "maxRetries must be greater than or equal to zero.")
        }
        if (retryBackoffMs != null && retryBackoffMs < 0L) {
            errors += error("executionPolicy.retryBackoffMs", DslValidationCode.INVALID_VALUE, "retryBackoffMs must be greater than or equal to zero.")
        }

        if (taskTimeoutMs == null || maxRetries == null || retryBackoffMs == null || conflictPolicy == null || onMissedSchedule == null) {
            return null
        }
        if (taskTimeoutMs <= 0L || maxRetries < 0 || retryBackoffMs < 0L) {
            return null
        }

        return ExecutionPolicy(
            taskTimeoutMs = taskTimeoutMs,
            maxRetries = maxRetries,
            retryBackoffMs = retryBackoffMs,
            conflictPolicy = conflictPolicy,
            onMissedSchedule = onMissedSchedule,
        )
    }

    private fun validateSteps(
        payload: List<StepPayload>?,
        errors: MutableList<DslValidationError>,
    ): List<TaskStep>? {
        if (payload == null) {
            errors += error("steps", DslValidationCode.MISSING_REQUIRED_FIELD, "steps is required.")
            return null
        }

        val stepIds = mutableSetOf<String>()
        val steps = mutableListOf<TaskStep>()

        payload.forEachIndexed { index, stepPayload ->
            val pathPrefix = "steps[$index]"
            val id = requiredString(stepPayload.id, "$pathPrefix.id", errors)
            if (id != null && !stepIds.add(id)) {
                errors += error("$pathPrefix.id", DslValidationCode.DUPLICATE_ID, "Step id must be unique.")
            }

            val type = when (stepPayload.type) {
                null -> {
                    errors += error("$pathPrefix.type", DslValidationCode.MISSING_REQUIRED_FIELD, "type is required.")
                    null
                }

                "start_app" -> StepType.START_APP
                "stop_app" -> StepType.STOP_APP
                "restart_app" -> StepType.RESTART_APP
                "tap" -> StepType.TAP
                "swipe" -> StepType.SWIPE
                "input_text" -> StepType.INPUT_TEXT
                "wait_element" -> StepType.WAIT_ELEMENT
                else -> {
                    errors += error("$pathPrefix.type", DslValidationCode.INVALID_ENUM, "Unsupported step type: ${stepPayload.type}.")
                    null
                }
            }

            val timeoutMs = requiredLong(stepPayload.timeoutMs, "$pathPrefix.timeoutMs", errors)
            if (timeoutMs != null && timeoutMs <= 0L) {
                errors += error("$pathPrefix.timeoutMs", DslValidationCode.INVALID_VALUE, "timeoutMs must be greater than zero.")
            }

            val retryPolicy = validateRetry(stepPayload.retry, "$pathPrefix.retry", errors)
            val onFailure = validateStepFailurePolicy(stepPayload.onFailure, "$pathPrefix.onFailure", errors)
            val clearsSensitiveContext = stepPayload.clearsSensitiveContext ?: false

            val params = stepPayload.params ?: run {
                errors += error("$pathPrefix.params", DslValidationCode.MISSING_REQUIRED_FIELD, "params is required.")
                null
            }

            if (type != null && params != null) {
                validateStepParams(type, params, pathPrefix, errors)
            }

            if (id != null && type != null && timeoutMs != null && timeoutMs > 0L && params != null && retryPolicy != null && onFailure != null) {
                steps += TaskStep(
                    id = id,
                    type = type,
                    name = stepPayload.name,
                    timeoutMs = timeoutMs,
                    retry = retryPolicy,
                    onFailure = onFailure,
                    clearsSensitiveContext = clearsSensitiveContext,
                    params = params,
                )
            }
        }

        return if (errors.any { it.path.startsWith("steps[") } || errors.any { it.path == "steps" }) {
            null
        } else {
            steps
        }
    }

    private fun validateRetry(
        payload: RetryPayload?,
        pathPrefix: String,
        errors: MutableList<DslValidationError>,
    ): StepRetryPolicy? {
        if (payload == null) {
            return StepRetryPolicy()
        }

        val maxRetries = payload.maxRetries ?: 0
        val backoffMs = payload.backoffMs ?: 1000L
        if (maxRetries < 0) {
            errors += error("$pathPrefix.maxRetries", DslValidationCode.INVALID_VALUE, "retry.maxRetries must be greater than or equal to zero.")
        }
        if (backoffMs < 0L) {
            errors += error("$pathPrefix.backoffMs", DslValidationCode.INVALID_VALUE, "retry.backoffMs must be greater than or equal to zero.")
        }
        return if (maxRetries < 0 || backoffMs < 0L) {
            null
        } else {
            StepRetryPolicy(maxRetries = maxRetries, backoffMs = backoffMs)
        }
    }

    private fun validateStepFailurePolicy(
        rawValue: String?,
        path: String,
        errors: MutableList<DslValidationError>,
    ): StepFailurePolicy? {
        return when (rawValue ?: "stop_task") {
            "stop_task" -> StepFailurePolicy.STOP_TASK
            "continue" -> StepFailurePolicy.CONTINUE
            "retry_task" -> StepFailurePolicy.RETRY_TASK
            else -> {
                errors += error(path, DslValidationCode.INVALID_ENUM, "Unsupported onFailure: $rawValue.")
                null
            }
        }
    }

    private fun validateStepParams(
        type: StepType,
        params: JsonObject,
        pathPrefix: String,
        errors: MutableList<DslValidationError>,
    ) {
        when (type) {
            StepType.START_APP,
            StepType.STOP_APP,
            -> requireJsonString(params, "packageName", "$pathPrefix.params.packageName", errors)

            StepType.RESTART_APP -> {
                requireJsonString(params, "packageName", "$pathPrefix.params.packageName", errors)
                requireJsonLong(params, "waitAfterStopMs", "$pathPrefix.params.waitAfterStopMs", errors)
            }

            StepType.TAP -> requireJsonValue(params, "target", "$pathPrefix.params.target", errors)

            StepType.SWIPE -> {
                requireJsonObject(params, "from", "$pathPrefix.params.from", errors)
                requireJsonObject(params, "to", "$pathPrefix.params.to", errors)
                requireJsonLong(params, "durationMs", "$pathPrefix.params.durationMs", errors)
            }

            StepType.INPUT_TEXT -> {
                val hasText = params["text"].asNonBlankString() != null
                val hasTextRef = params["textRef"].asNonBlankString() != null
                if (hasText == hasTextRef) {
                    errors += error(
                        path = "$pathPrefix.params",
                        code = DslValidationCode.CONFLICTING_FIELDS,
                        message = "input_text requires exactly one of text or textRef.",
                    )
                }
                if (params["selector"] !is JsonObject) {
                    errors += error(
                        path = "$pathPrefix.params.selector",
                        code = DslValidationCode.MISSING_REQUIRED_FIELD,
                        message = "selector is required for input_text.",
                    )
                }
            }

            StepType.WAIT_ELEMENT -> {
                requireJsonObject(params, "selector", "$pathPrefix.params.selector", errors)
                requireJsonString(params, "state", "$pathPrefix.params.state", errors)
            }

            else -> Unit
        }
    }

    private fun validateDiagnostics(payload: DiagnosticsPayload?): DiagnosticsPolicy {
        if (payload == null) {
            return DiagnosticsPolicy()
        }

        return DiagnosticsPolicy(
            captureScreenshotOnFailure = payload.captureScreenshotOnFailure ?: true,
            captureScreenshotOnStepFailure = payload.captureScreenshotOnStepFailure ?: true,
            logLevel = payload.logLevel ?: "info",
        )
    }

    private fun requiredString(
        value: String?,
        path: String,
        errors: MutableList<DslValidationError>,
    ): String? {
        if (value.isNullOrBlank()) {
            errors += error(path, DslValidationCode.MISSING_REQUIRED_FIELD, "$path is required.")
            return null
        }
        return value
    }

    private fun requiredLong(
        value: Long?,
        path: String,
        errors: MutableList<DslValidationError>,
    ): Long? {
        if (value == null) {
            errors += error(path, DslValidationCode.MISSING_REQUIRED_FIELD, "$path is required.")
        }
        return value
    }

    private fun requiredInt(
        value: Int?,
        path: String,
        errors: MutableList<DslValidationError>,
    ): Int? {
        if (value == null) {
            errors += error(path, DslValidationCode.MISSING_REQUIRED_FIELD, "$path is required.")
        }
        return value
    }

    private fun requireJsonString(
        source: JsonObject,
        key: String,
        path: String,
        errors: MutableList<DslValidationError>,
    ): String? {
        val value = source[key].asNonBlankString()
        if (value == null) {
            errors += error(path, DslValidationCode.MISSING_REQUIRED_FIELD, "$path is required.")
        }
        return value
    }

    private fun requireJsonLong(
        source: JsonObject,
        key: String,
        path: String,
        errors: MutableList<DslValidationError>,
    ): Long? {
        val value = source[key].asLong()
        if (value == null) {
            errors += error(path, DslValidationCode.MISSING_REQUIRED_FIELD, "$path is required.")
        }
        return value
    }

    private fun requireJsonObject(
        source: JsonObject,
        key: String,
        path: String,
        errors: MutableList<DslValidationError>,
    ): JsonObject? {
        val value = source[key] as? JsonObject
        if (value == null) {
            errors += error(path, DslValidationCode.MISSING_REQUIRED_FIELD, "$path is required.")
        }
        return value
    }

    private fun requireJsonValue(
        source: JsonObject,
        key: String,
        path: String,
        errors: MutableList<DslValidationError>,
    ): JsonElement? {
        val value = source[key]
        if (value == null || value is JsonNull) {
            errors += error(path, DslValidationCode.MISSING_REQUIRED_FIELD, "$path is required.")
            return null
        }
        return value
    }

    private fun isFivePartCron(expression: String): Boolean = expression.trim().split(CRON_SPLIT_REGEX).size == 5

    private fun error(path: String, code: DslValidationCode, message: String) = DslValidationError(
        path = path,
        code = code,
        message = message,
    )

    private fun JsonElement?.asNonBlankString(): String? {
        return (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun JsonElement?.asLong(): Long? {
        return (this as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
    }

    companion object {
        private const val SUPPORTED_SCHEMA_VERSION = "1.0"
        private val TASK_ID_REGEX = Regex("^[a-z0-9-]+$")
        private val CRON_SPLIT_REGEX = Regex("\\s+")
    }
}

@Serializable
private data class TaskDefinitionPayload(
    val schemaVersion: String? = null,
    val taskId: String? = null,
    val name: String? = null,
    val description: String? = null,
    val enabled: Boolean? = null,
    val targetApp: TargetAppPayload? = null,
    val trigger: TriggerPayload? = null,
    val accountRotation: JsonObject? = null,
    val executionPolicy: ExecutionPolicyPayload? = null,
    val preconditions: List<JsonObject>? = null,
    val variables: JsonObject? = null,
    val steps: List<StepPayload>? = null,
    val diagnostics: DiagnosticsPayload? = null,
    val tags: List<String>? = null,
)

@Serializable
private data class TargetAppPayload(
    val packageName: String? = null,
    val launchActivity: String? = null,
)

@Serializable
private data class TriggerPayload(
    val type: String? = null,
    val expression: String? = null,
    val timezone: String? = null,
    val cooldownMs: Long? = null,
    val maxCycles: Int? = null,
    val maxDurationMs: Long? = null,
)

@Serializable
private data class ExecutionPolicyPayload(
    val taskTimeoutMs: Long? = null,
    val maxRetries: Int? = null,
    val retryBackoffMs: Long? = null,
    val conflictPolicy: String? = null,
    val onMissedSchedule: String? = null,
)

@Serializable
private data class DiagnosticsPayload(
    val captureScreenshotOnFailure: Boolean? = null,
    val captureScreenshotOnStepFailure: Boolean? = null,
    val logLevel: String? = null,
)

@Serializable
private data class StepPayload(
    val id: String? = null,
    val type: String? = null,
    val name: String? = null,
    val timeoutMs: Long? = null,
    val retry: RetryPayload? = null,
    val onFailure: String? = null,
    val clearsSensitiveContext: Boolean? = null,
    val params: JsonObject? = null,
)

@Serializable
private data class RetryPayload(
    val maxRetries: Int? = null,
    val backoffMs: Long? = null,
)
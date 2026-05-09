package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskScheduleStateRecord
import com.plearn.appcontrol.data.repository.TaskRepository
import com.plearn.appcontrol.dsl.TaskDefinition
import com.plearn.appcontrol.dsl.TaskDslParser
import com.plearn.appcontrol.dsl.TaskTrigger
import com.plearn.appcontrol.runner.TaskExecutionResult
import com.plearn.appcontrol.scheduler.CronScheduleCalculator
import com.plearn.appcontrol.scheduler.SchedulerTimeSource
import javax.inject.Inject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

data class TaskImportResult(
    val saved: Boolean,
    val taskId: String?,
    val name: String?,
    val definitionStatus: String,
    val errors: List<String>,
)

data class TaskToggleResult(
    val updated: Boolean,
    val taskId: String,
    val enabled: Boolean,
    val errorMessage: String? = null,
)

data class TaskRunNowResult(
    val execution: TaskExecutionResult? = null,
    val errorMessage: String? = null,
)

interface SchedulerStandbyController {
    fun syncActiveSchedules(hasActiveSchedules: Boolean)
}

class TaskManagementService @Inject constructor(
    private val taskRepository: TaskRepository,
    private val parser: TaskDslParser,
    private val manualTaskExecutionService: ManualTaskExecutionService,
    private val schedulerTimeSource: SchedulerTimeSource,
    private val cronScheduleCalculator: CronScheduleCalculator,
    private val schedulerStandbyController: SchedulerStandbyController,
) {
    suspend fun importTask(rawJson: String): TaskImportResult {
        val parseResult = parser.parse(rawJson)
        val task = parseResult.task
        if (task == null) {
            return TaskImportResult(
                saved = false,
                taskId = null,
                name = null,
                definitionStatus = parseResult.definitionStatus.name.lowercase(),
                errors = parseResult.errors.map { error -> "${error.path}: ${error.message}" },
            )
        }

        val nowMs = schedulerTimeSource.nowMs()
        val definitionStatus = parseResult.definitionStatus.name.lowercase()
        val scheduleState = runCatching {
            createScheduleState(
                task = task,
                definitionStatus = definitionStatus,
                nowMs = nowMs,
            )
        }.getOrElse { error ->
            return TaskImportResult(
                saved = false,
                taskId = task.taskId,
                name = task.name,
                definitionStatus = definitionStatus,
                errors = listOf(error.message ?: "Unable to initialize scheduler state."),
            )
        }
        val taskDefinition = TaskDefinitionRecord(
            taskId = task.taskId,
            name = task.name,
            enabled = task.enabled,
            triggerType = task.trigger.toTriggerTypeValue(),
            definitionStatus = definitionStatus,
            rawJson = rawJson,
            updatedAt = nowMs,
        )

        taskRepository.upsertTaskDefinition(taskDefinition)
        taskRepository.upsertScheduleState(scheduleState)
        syncSchedulerStandbyState()

        return TaskImportResult(
            saved = true,
            taskId = task.taskId,
            name = task.name,
            definitionStatus = definitionStatus,
            errors = emptyList(),
        )
    }

    suspend fun setTaskEnabled(taskId: String, enabled: Boolean): TaskToggleResult {
        val existing = taskRepository.getTaskDefinition(taskId)
            ?: return TaskToggleResult(
                updated = false,
                taskId = taskId,
                enabled = enabled,
                errorMessage = "Task definition $taskId was not found.",
            )

        val nowMs = schedulerTimeSource.nowMs()
        val previousState = taskRepository.getScheduleState(taskId)
        val updatedRawJson = rewriteEnabledFlag(existing.rawJson, enabled)
        val updatedDefinition = existing.copy(
            enabled = enabled,
            rawJson = updatedRawJson,
            updatedAt = nowMs,
        )

        val scheduleState = when {
            !enabled || !existing.definitionStatus.equals(READY_STATUS, ignoreCase = true) -> disabledScheduleState(
                taskId = taskId,
                previousState = previousState,
            )

            else -> {
                val parsedTask = parser.parse(updatedRawJson).task
                if (parsedTask == null) {
                    return TaskToggleResult(
                        updated = false,
                        taskId = taskId,
                        enabled = enabled,
                        errorMessage = "Task definition $taskId is no longer valid and cannot be enabled.",
                    )
                }
                runCatching {
                    createScheduleState(
                        task = parsedTask,
                        definitionStatus = existing.definitionStatus,
                        nowMs = nowMs,
                        previousState = previousState,
                    )
                }.getOrElse { error ->
                    return TaskToggleResult(
                        updated = false,
                        taskId = taskId,
                        enabled = enabled,
                        errorMessage = error.message ?: "Unable to initialize scheduler state for $taskId.",
                    )
                }
            }
        }

        taskRepository.upsertTaskDefinition(updatedDefinition)
        taskRepository.upsertScheduleState(scheduleState)
        syncSchedulerStandbyState()

        return TaskToggleResult(updated = true, taskId = taskId, enabled = enabled)
    }

    suspend fun loadTaskRawJson(taskId: String): String? = taskRepository.getTaskDefinition(taskId)?.rawJson

    suspend fun runTaskNow(taskId: String): TaskRunNowResult {
        val taskDefinition = taskRepository.getTaskDefinition(taskId)
            ?: return TaskRunNowResult(errorMessage = "Task definition $taskId was not found.")
        return TaskRunNowResult(execution = manualTaskExecutionService.run(taskDefinition))
    }

    private suspend fun syncSchedulerStandbyState() {
        var hasActiveSchedules = false
        for (definition in taskRepository.listTaskDefinitions()) {
            if (shouldKeepSchedulerRunning(definition)) {
                hasActiveSchedules = true
                break
            }
        }
        schedulerStandbyController.syncActiveSchedules(hasActiveSchedules)
    }

    private suspend fun shouldKeepSchedulerRunning(definition: TaskDefinitionRecord): Boolean {
        if (!definition.enabled || !definition.definitionStatus.equals(READY_STATUS, ignoreCase = true)) {
            return false
        }
        if (definition.triggerType != CRON_TRIGGER && definition.triggerType != CONTINUOUS_TRIGGER) {
            return false
        }

        val scheduleState = taskRepository.getScheduleState(definition.taskId) ?: return true
        return scheduleState.standbyEnabled && scheduleState.nextTriggerAt != null
    }

    private fun createScheduleState(
        task: TaskDefinition,
        definitionStatus: String,
        nowMs: Long,
        previousState: TaskScheduleStateRecord? = null,
    ): TaskScheduleStateRecord {
        val standbyEnabled = task.enabled && definitionStatus.equals(READY_STATUS, ignoreCase = true)
        val nextTriggerAt = if (!standbyEnabled) {
            null
        } else {
            when (val trigger = task.trigger) {
                is TaskTrigger.Cron -> cronScheduleCalculator.nextTriggerAt(trigger.expression, trigger.timezone, nowMs)
                is TaskTrigger.Continuous -> nowMs
            }
        }
        return TaskScheduleStateRecord(
            taskId = task.taskId,
            nextTriggerAt = nextTriggerAt,
            standbyEnabled = standbyEnabled,
            lastTriggerAt = previousState?.lastTriggerAt,
            lastScheduleStatus = previousState?.lastScheduleStatus,
        )
    }

    private fun disabledScheduleState(
        taskId: String,
        previousState: TaskScheduleStateRecord?,
    ): TaskScheduleStateRecord = TaskScheduleStateRecord(
        taskId = taskId,
        nextTriggerAt = null,
        standbyEnabled = false,
        lastTriggerAt = previousState?.lastTriggerAt,
        lastScheduleStatus = previousState?.lastScheduleStatus,
    )

    private fun rewriteEnabledFlag(rawJson: String, enabled: Boolean): String {
        return runCatching {
            val root = json.parseToJsonElement(rawJson) as? JsonObject ?: return rawJson
            val updatedRoot = buildJsonObject {
                root.forEach { (key, value) ->
                    put(key, if (key == ENABLED_FIELD) JsonPrimitive(enabled) else value)
                }
                if (!root.containsKey(ENABLED_FIELD)) {
                    put(ENABLED_FIELD, JsonPrimitive(enabled))
                }
            }
            updatedRoot.toString()
        }.getOrDefault(rawJson)
    }

    private fun TaskTrigger.toTriggerTypeValue(): String = when (this) {
        is TaskTrigger.Cron -> CRON_TRIGGER
        is TaskTrigger.Continuous -> CONTINUOUS_TRIGGER
    }

    private companion object {
        const val READY_STATUS = "ready"
        const val CRON_TRIGGER = "cron"
        const val CONTINUOUS_TRIGGER = "continuous"
        const val ENABLED_FIELD = "enabled"
        val json: Json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    }
}
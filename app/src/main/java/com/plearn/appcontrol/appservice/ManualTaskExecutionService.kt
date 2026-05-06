package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.dsl.TaskDslParser
import com.plearn.appcontrol.runner.RunTriggerType
import com.plearn.appcontrol.runner.RunnerFailureCode
import com.plearn.appcontrol.runner.RunnerTimeSource
import com.plearn.appcontrol.runner.SystemRunnerTimeSource
import com.plearn.appcontrol.runner.TaskExecutionResult
import com.plearn.appcontrol.runner.TaskRunStatus
import com.plearn.appcontrol.runner.TaskRunner
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ManualTaskExecutionService @Inject constructor(
    private val parser: TaskDslParser,
    private val taskRunner: TaskRunner,
    private val timeSource: RunnerTimeSource = SystemRunnerTimeSource,
    private val runIdFactory: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun run(taskDefinition: TaskDefinitionRecord): TaskExecutionResult {
        if (taskDefinition.definitionStatus.lowercase() != "ready") {
            return blocked(
                taskId = taskDefinition.taskId,
                errorCode = RunnerFailureCode.RUNNER_TASK_NOT_READY,
                message = "Task definition ${taskDefinition.taskId} is not ready for manual execution.",
            )
        }

        val parseResult = parser.parse(taskDefinition.rawJson)
        val task = parseResult.task
        if (task == null) {
            return blocked(
                taskId = taskDefinition.taskId,
                errorCode = RunnerFailureCode.RUNNER_DSL_INVALID,
                message = parseResult.errors.joinToString(separator = "; ") { "${it.path}: ${it.message}" },
            )
        }

        return taskRunner.run(task = task, triggerType = RunTriggerType.MANUAL)
    }

    private fun blocked(
        taskId: String,
        errorCode: String,
        message: String,
    ): TaskExecutionResult {
        val now = timeSource.nowMs()
        return TaskExecutionResult(
            taskRun = TaskRunRecord(
                runId = runIdFactory(),
                sessionId = null,
                cycleNo = null,
                taskId = taskId,
                credentialSetId = null,
                credentialProfileId = null,
                credentialAlias = null,
                status = TaskRunStatus.BLOCKED,
                startedAt = now,
                finishedAt = now,
                durationMs = 0L,
                triggerType = RunTriggerType.MANUAL,
                errorCode = errorCode,
                message = message,
            ),
            stepRuns = emptyList(),
            taskAttemptCount = 0,
        )
    }
}
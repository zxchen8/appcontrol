package com.plearn.appcontrol.runner

import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import kotlinx.coroutines.delay

interface TaskRunner {
    suspend fun run(task: com.plearn.appcontrol.dsl.TaskDefinition, triggerType: String = RunTriggerType.MANUAL): TaskExecutionResult
}

data class TaskExecutionResult(
    val taskRun: TaskRunRecord,
    val stepRuns: List<StepRunRecord>,
    val taskAttemptCount: Int,
)

interface RunnerTimeSource {
    fun nowMs(): Long

    suspend fun delay(durationMs: Long)
}

object SystemRunnerTimeSource : RunnerTimeSource {
    override fun nowMs(): Long = System.currentTimeMillis()

    override suspend fun delay(durationMs: Long) {
        delay(durationMs)
    }
}

object RunTriggerType {
    const val MANUAL = "manual"
    const val CRON = "cron"
    const val CONTINUOUS = "continuous"
}

object TaskRunStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val SUCCESS = "success"
    const val FAILED = "failed"
    const val TIMED_OUT = "timed_out"
    const val CANCELLED = "cancelled"
    const val BLOCKED = "blocked"
}

object StepRunStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val SUCCESS = "success"
    const val FAILED = "failed"
    const val TIMED_OUT = "timed_out"
    const val SKIPPED = "skipped"
    const val CANCELLED = "cancelled"
}

object RunnerFailureCode {
    const val RUNNER_TASK_NOT_READY = "RUNNER_TASK_NOT_READY"
    const val RUNNER_DSL_INVALID = "RUNNER_DSL_INVALID"
    const val RUNNER_UNSUPPORTED_STEP = "RUNNER_UNSUPPORTED_STEP"
    const val RUNNER_INVALID_STEP_ARGUMENT = "RUNNER_INVALID_STEP_ARGUMENT"
    const val RUNNER_TEXT_REFERENCE_UNRESOLVED = "RUNNER_TEXT_REFERENCE_UNRESOLVED"
    const val RUNNER_TASK_CANCELLED = "RUNNER_TASK_CANCELLED"
}
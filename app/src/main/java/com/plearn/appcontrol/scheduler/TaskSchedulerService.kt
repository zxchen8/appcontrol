package com.plearn.appcontrol.scheduler

import com.plearn.appcontrol.data.model.ContinuousSessionRecord
import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskScheduleStateRecord
import com.plearn.appcontrol.data.repository.CredentialRepository
import com.plearn.appcontrol.data.repository.SessionRepository
import com.plearn.appcontrol.data.repository.TaskRepository
import com.plearn.appcontrol.dsl.TaskDefinition
import com.plearn.appcontrol.dsl.TaskDslParser
import com.plearn.appcontrol.dsl.TaskTrigger
import com.plearn.appcontrol.runner.RunTriggerType
import com.plearn.appcontrol.runner.TaskExecutionRecorder
import com.plearn.appcontrol.runner.TaskRunStatus
import com.plearn.appcontrol.runner.TaskRunner
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

class TaskSchedulerService(
    private val parser: TaskDslParser,
    private val taskRepository: TaskRepository,
    private val credentialRepository: CredentialRepository,
    private val sessionRepository: SessionRepository,
    private val taskRunner: TaskRunner,
    private val executionRecorder: TaskExecutionRecorder,
    private val timeSource: SchedulerTimeSource = SystemSchedulerTimeSource,
    private val cronScheduleCalculator: CronScheduleCalculator = CronScheduleCalculator(),
    private val sessionIdFactory: () -> String,
) {
    suspend fun dispatchDueTasks(): SchedulerDispatchResult {
        val nowMs = timeSource.nowMs()
        val executableTasks = mutableListOf<ExecutableTask>()

        for (definitionRecord in taskRepository.listTaskDefinitions()) {
            val parsedTask = parseExecutableTask(definitionRecord) ?: continue
            val scheduleState = taskRepository.getScheduleState(definitionRecord.taskId)
                ?: createInitialScheduleState(parsedTask.task, nowMs)
            if (taskRepository.getScheduleState(definitionRecord.taskId) == null) {
                taskRepository.upsertScheduleState(scheduleState)
            }

            if (!definitionRecord.enabled || !definitionRecord.definitionStatus.equals("ready", ignoreCase = true)) {
                continue
            }

            if (scheduleState.nextTriggerAt != null && scheduleState.nextTriggerAt <= nowMs) {
                executableTasks += ExecutableTask(
                    definitionRecord = definitionRecord,
                    task = parsedTask.task,
                    scheduleState = scheduleState,
                )
            }
        }

        val executedTaskIds = mutableListOf<String>()
        for (executableTask in executableTasks.sortedWith(compareBy<ExecutableTask>({ if (it.task.trigger is TaskTrigger.Cron) 0 else 1 }, { it.scheduleState.nextTriggerAt ?: Long.MAX_VALUE }, { it.definitionRecord.taskId }))) {
            when (executableTask.task.trigger) {
                is TaskTrigger.Cron -> executeCronTask(executableTask, nowMs)
                is TaskTrigger.Continuous -> executeContinuousTask(executableTask, nowMs)
            }
            executedTaskIds += executableTask.definitionRecord.taskId
        }

        return SchedulerDispatchResult(executedTaskIds = executedTaskIds)
    }

    private suspend fun executeCronTask(executableTask: ExecutableTask, nowMs: Long) {
        val execution = executionRecorder.record(
            result = taskRunner.run(executableTask.task, RunTriggerType.CRON),
        )
        val trigger = executableTask.task.trigger as TaskTrigger.Cron
        taskRepository.upsertScheduleState(
            executableTask.scheduleState.copy(
                nextTriggerAt = cronScheduleCalculator.nextTriggerAt(trigger.expression, trigger.timezone, nowMs),
                standbyEnabled = executableTask.definitionRecord.enabled,
                lastTriggerAt = nowMs,
                lastScheduleStatus = if (execution.taskRun.status == TaskRunStatus.BLOCKED) ScheduleStatus.BLOCKED else ScheduleStatus.SCHEDULED,
            ),
        )
    }

    private suspend fun executeContinuousTask(executableTask: ExecutableTask, nowMs: Long) {
        val trigger = executableTask.task.trigger as TaskTrigger.Continuous
        val runningSession = sessionRepository.findRunningSession(executableTask.definitionRecord.taskId)
        val credentialSetId = executableTask.task.accountRotation.stringValue("credentialSetId") ?: run {
            blockContinuousTask(
                executableTask = executableTask,
                runningSession = runningSession,
                nowMs = nowMs,
                errorCode = SchedulerFailureCode.SCHEDULER_CREDENTIAL_SET_UNAVAILABLE,
            )
            return
        }
        if (credentialRepository.getCredentialSet(credentialSetId) == null) {
            blockContinuousTask(
                executableTask = executableTask,
                runningSession = runningSession,
                nowMs = nowMs,
                errorCode = SchedulerFailureCode.SCHEDULER_CREDENTIAL_SET_UNAVAILABLE,
            )
            return
        }

        val activeSession = runningSession
            ?: ContinuousSessionRecord(
                sessionId = sessionIdFactory(),
                taskId = executableTask.definitionRecord.taskId,
                credentialSetId = credentialSetId,
                status = "running",
                startedAt = nowMs,
                finishedAt = null,
                totalCycles = 0,
                successCycles = 0,
                failedCycles = 0,
                currentCredentialProfileId = null,
                currentCredentialAlias = null,
                nextCredentialProfileId = null,
                nextCredentialAlias = null,
                cursorIndex = 0,
                lastErrorCode = null,
            )

        val cycleNo = activeSession.totalCycles + 1
        val execution = executionRecorder.record(
            result = taskRunner.run(executableTask.task, RunTriggerType.CONTINUOUS),
            sessionId = activeSession.sessionId,
            cycleNo = cycleNo,
        )

        val successCycles = activeSession.successCycles + if (execution.taskRun.status == TaskRunStatus.SUCCESS) 1 else 0
        val failedCycles = activeSession.failedCycles + if (execution.taskRun.status == TaskRunStatus.SUCCESS) 0 else 1
        val totalCycles = activeSession.totalCycles + 1
        val reachedMaxCycles = trigger.maxCycles != null && totalCycles >= trigger.maxCycles
        val reachedMaxDuration = trigger.maxDurationMs != null && nowMs - activeSession.startedAt >= trigger.maxDurationMs

        if (reachedMaxCycles || reachedMaxDuration || execution.taskRun.status == TaskRunStatus.BLOCKED) {
            val terminalStatus = when {
                execution.taskRun.status == TaskRunStatus.BLOCKED -> TaskRunStatus.BLOCKED
                reachedMaxDuration -> TaskRunStatus.TIMED_OUT
                else -> TaskRunStatus.SUCCESS
            }
            sessionRepository.updateTerminalState(
                sessionId = activeSession.sessionId,
                status = terminalStatus,
                finishedAt = nowMs,
                totalCycles = totalCycles,
                successCycles = successCycles,
                failedCycles = failedCycles,
                lastErrorCode = execution.taskRun.errorCode,
            )
            taskRepository.upsertScheduleState(
                executableTask.scheduleState.copy(
                    nextTriggerAt = null,
                    standbyEnabled = false,
                    lastTriggerAt = nowMs,
                    lastScheduleStatus = if (terminalStatus == TaskRunStatus.BLOCKED) ScheduleStatus.BLOCKED else ScheduleStatus.SCHEDULED,
                ),
            )
            return
        }

        sessionRepository.upsertSession(
            activeSession.copy(
                status = "running",
                totalCycles = totalCycles,
                successCycles = successCycles,
                failedCycles = failedCycles,
                lastErrorCode = execution.taskRun.errorCode,
            ),
        )
        taskRepository.upsertScheduleState(
            executableTask.scheduleState.copy(
                nextTriggerAt = nowMs + trigger.cooldownMs,
                standbyEnabled = executableTask.definitionRecord.enabled,
                lastTriggerAt = nowMs,
                lastScheduleStatus = ScheduleStatus.SCHEDULED,
            ),
        )
    }

    private suspend fun blockContinuousTask(
        executableTask: ExecutableTask,
        runningSession: ContinuousSessionRecord?,
        nowMs: Long,
        errorCode: String,
    ) {
        if (runningSession != null) {
            sessionRepository.updateTerminalState(
                sessionId = runningSession.sessionId,
                status = TaskRunStatus.BLOCKED,
                finishedAt = nowMs,
                totalCycles = runningSession.totalCycles,
                successCycles = runningSession.successCycles,
                failedCycles = runningSession.failedCycles,
                lastErrorCode = errorCode,
            )
        }

        taskRepository.upsertScheduleState(
            executableTask.scheduleState.copy(
                nextTriggerAt = null,
                standbyEnabled = false,
                lastTriggerAt = nowMs,
                lastScheduleStatus = ScheduleStatus.BLOCKED,
            ),
        )
    }

    private fun parseExecutableTask(definitionRecord: TaskDefinitionRecord): ParsedTask? {
        val parseResult = parser.parse(definitionRecord.rawJson)
        val task = parseResult.task ?: return null
        return ParsedTask(task = task)
    }

    private fun createInitialScheduleState(task: TaskDefinition, nowMs: Long): TaskScheduleStateRecord =
        TaskScheduleStateRecord(
            taskId = task.taskId,
            nextTriggerAt = when (val trigger = task.trigger) {
                is TaskTrigger.Cron -> cronScheduleCalculator.nextTriggerAt(trigger.expression, trigger.timezone, nowMs)
                is TaskTrigger.Continuous -> nowMs
            },
            standbyEnabled = task.enabled,
            lastTriggerAt = null,
            lastScheduleStatus = ScheduleStatus.IDLE,
        )

    private fun JsonObject?.stringValue(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.contentOrNull

    private data class ParsedTask(
        val task: TaskDefinition,
    )

    private data class ExecutableTask(
        val definitionRecord: TaskDefinitionRecord,
        val task: TaskDefinition,
        val scheduleState: TaskScheduleStateRecord,
    )
}
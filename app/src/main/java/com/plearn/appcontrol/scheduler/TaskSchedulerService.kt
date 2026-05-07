package com.plearn.appcontrol.scheduler

import com.plearn.appcontrol.data.model.ContinuousSessionRecord
import com.plearn.appcontrol.data.model.CredentialProfileRecord
import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskScheduleStateRecord
import com.plearn.appcontrol.data.repository.CredentialRepository
import com.plearn.appcontrol.data.repository.SessionRepository
import com.plearn.appcontrol.data.repository.TaskRepository
import com.plearn.appcontrol.dsl.ConflictPolicy
import com.plearn.appcontrol.dsl.MissedSchedulePolicy
import com.plearn.appcontrol.dsl.TaskDefinition
import com.plearn.appcontrol.dsl.TaskDslParser
import com.plearn.appcontrol.dsl.TaskTrigger
import com.plearn.appcontrol.runner.RunTriggerType
import com.plearn.appcontrol.runner.TaskExecutionRecorder
import com.plearn.appcontrol.runner.TaskExecutionResult
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
    private val executionLock: TaskExecutionLock = InMemoryTaskExecutionLock(),
    private val sessionIdFactory: () -> String,
) {
    suspend fun dispatchDueTasks(mode: SchedulerDispatchMode = SchedulerDispatchMode.NORMAL): SchedulerDispatchResult {
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
        val continuousPackagesExecuted = mutableSetOf<String>()
        for (executableTask in executableTasks.sortedWith(compareBy<ExecutableTask>({ if (it.task.trigger is TaskTrigger.Cron) 0 else 1 }, { it.scheduleState.nextTriggerAt ?: Long.MAX_VALUE }, { it.definitionRecord.taskId }))) {
            if (mode == SchedulerDispatchMode.RECOVERY && shouldSkipMissedSchedule(executableTask, nowMs)) {
                skipMissedTask(executableTask, nowMs)
                continue
            }

            if (executableTask.task.trigger is TaskTrigger.Continuous && !shouldDispatchContinuousTask(executableTask, continuousPackagesExecuted)) {
                continue
            }

            if (executeWithLock(executableTask, nowMs)) {
                executedTaskIds += executableTask.definitionRecord.taskId
                if (executableTask.task.trigger is TaskTrigger.Continuous) {
                    continuousPackagesExecuted += executableTask.task.targetApp.packageName
                }
            }
        }

        return SchedulerDispatchResult(executedTaskIds = executedTaskIds)
    }

    private fun shouldDispatchContinuousTask(
        executableTask: ExecutableTask,
        continuousPackagesExecuted: Set<String>,
    ): Boolean = executableTask.task.targetApp.packageName !in continuousPackagesExecuted

    private fun shouldSkipMissedSchedule(executableTask: ExecutableTask, nowMs: Long): Boolean {
        if (executableTask.task.trigger !is TaskTrigger.Cron) {
            return false
        }
        val scheduledAt = executableTask.scheduleState.nextTriggerAt ?: return false
        return scheduledAt < nowMs && executableTask.task.executionPolicy.onMissedSchedule == MissedSchedulePolicy.SKIP
    }

    private suspend fun skipMissedTask(executableTask: ExecutableTask, nowMs: Long) {
        val trigger = executableTask.task.trigger as? TaskTrigger.Cron ?: return
        val scheduledAt = executableTask.scheduleState.nextTriggerAt ?: nowMs
        taskRepository.upsertScheduleState(
            executableTask.scheduleState.copy(
                nextTriggerAt = cronScheduleCalculator.nextTriggerAt(trigger.expression, trigger.timezone, nowMs),
                standbyEnabled = executableTask.definitionRecord.enabled,
                lastTriggerAt = scheduledAt,
                lastScheduleStatus = ScheduleStatus.MISSED_SKIPPED,
            ),
        )
    }

    private suspend fun executeWithLock(executableTask: ExecutableTask, nowMs: Long): Boolean {
        val packageName = executableTask.task.targetApp.packageName
        if (!executionLock.tryAcquire(packageName)) {
            handleExecutionLockConflict(executableTask, nowMs)
            return false
        }

        return try {
            when (executableTask.task.trigger) {
                is TaskTrigger.Cron -> executeCronTask(executableTask, nowMs)
                is TaskTrigger.Continuous -> executeContinuousTask(executableTask, nowMs)
            }
            true
        } finally {
            executionLock.release(packageName)
        }
    }

    private suspend fun handleExecutionLockConflict(executableTask: ExecutableTask, nowMs: Long) {
        when (val trigger = executableTask.task.trigger) {
            is TaskTrigger.Cron -> {
                val scheduledAt = executableTask.scheduleState.nextTriggerAt ?: nowMs
                when (executableTask.task.executionPolicy.conflictPolicy) {
                    ConflictPolicy.SKIP -> {
                        taskRepository.upsertScheduleState(
                            executableTask.scheduleState.copy(
                                nextTriggerAt = cronScheduleCalculator.nextTriggerAt(trigger.expression, trigger.timezone, nowMs),
                                standbyEnabled = executableTask.definitionRecord.enabled,
                                lastTriggerAt = scheduledAt,
                                lastScheduleStatus = ScheduleStatus.CONFLICT_SKIPPED,
                            ),
                        )
                    }

                    ConflictPolicy.RUN_AFTER_CURRENT -> {
                        taskRepository.upsertScheduleState(
                            executableTask.scheduleState.copy(
                                nextTriggerAt = nowMs,
                                standbyEnabled = executableTask.definitionRecord.enabled,
                                lastTriggerAt = scheduledAt,
                                lastScheduleStatus = ScheduleStatus.CONFLICT_DELAYED,
                            ),
                        )
                    }
                }
            }

            is TaskTrigger.Continuous -> {
                taskRepository.upsertScheduleState(
                    executableTask.scheduleState.copy(
                        nextTriggerAt = executableTask.scheduleState.nextTriggerAt ?: nowMs,
                        standbyEnabled = executableTask.definitionRecord.enabled,
                        lastScheduleStatus = ScheduleStatus.CONFLICT_DELAYED,
                    ),
                )
            }
        }
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
        val credentialSet = credentialRepository.getCredentialSet(credentialSetId)
        if (credentialSet == null) {
            blockContinuousTask(
                executableTask = executableTask,
                runningSession = runningSession,
                nowMs = nowMs,
                errorCode = SchedulerFailureCode.SCHEDULER_CREDENTIAL_SET_UNAVAILABLE,
            )
            return
        }

        val credentialAliasByProfileId = credentialRepository.getEnabledProfiles()
            .associateBy(CredentialProfileRecord::profileId, CredentialProfileRecord::alias)
        val rotation = resolveCredentialRotation(
            credentialSet = credentialSet,
            aliasByProfileId = credentialAliasByProfileId,
            cursorIndex = runningSession?.cursorIndex ?: 0,
        ) ?: run {
            blockContinuousTask(
                executableTask = executableTask,
                runningSession = runningSession,
                nowMs = nowMs,
                errorCode = SchedulerFailureCode.SCHEDULER_CREDENTIAL_SET_UNAVAILABLE,
            )
            return
        }
        val stopSessionOnFailure = executableTask.task.accountRotation.onCycleFailureStopSession()

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
                cursorIndex = rotation.currentIndex,
                lastErrorCode = null,
            )

        if (runningSession == null) {
            sessionRepository.upsertSession(activeSession)
        }

        val cycleNo = activeSession.totalCycles + 1
        val execution = executionRecorder.record(
            result = taskRunner.run(executableTask.task, RunTriggerType.CONTINUOUS).withCredentialContext(
                credentialSetId = credentialSetId,
                credentialProfileId = rotation.current.profileId,
                credentialAlias = rotation.current.alias,
            ),
            sessionId = activeSession.sessionId,
            cycleNo = cycleNo,
        )

        val successCycles = activeSession.successCycles + if (execution.taskRun.status == TaskRunStatus.SUCCESS) 1 else 0
        val failedCycles = activeSession.failedCycles + if (execution.taskRun.status == TaskRunStatus.SUCCESS) 0 else 1
        val totalCycles = activeSession.totalCycles + 1
        val reachedMaxCycles = trigger.maxCycles != null && totalCycles >= trigger.maxCycles
        val reachedMaxDuration = trigger.maxDurationMs != null && nowMs - activeSession.startedAt >= trigger.maxDurationMs
        val shouldStopOnFailure = execution.taskRun.status == TaskRunStatus.FAILED && stopSessionOnFailure

        if (reachedMaxCycles || reachedMaxDuration || execution.taskRun.status == TaskRunStatus.BLOCKED || shouldStopOnFailure) {
            val terminalStatus = when {
                execution.taskRun.status == TaskRunStatus.BLOCKED -> TaskRunStatus.BLOCKED
                shouldStopOnFailure -> TaskRunStatus.FAILED
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
                currentCredentialProfileId = rotation.current.profileId,
                currentCredentialAlias = rotation.current.alias,
                nextCredentialProfileId = rotation.next.profileId,
                nextCredentialAlias = rotation.next.alias,
                cursorIndex = rotation.nextIndex,
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

    private fun JsonObject?.onCycleFailureStopSession(): Boolean =
        stringValue("onCycleFailure") == "stop_session"

    private fun resolveCredentialRotation(
        credentialSet: com.plearn.appcontrol.data.model.CredentialSetRecord,
        aliasByProfileId: Map<String, String>,
        cursorIndex: Int,
    ): CredentialRotation? {
        val enabledCredentials = credentialSet.items
            .asSequence()
            .filter { it.enabled }
            .sortedBy { it.orderNo }
            .mapNotNull { item ->
                aliasByProfileId[item.profileId]?.let { alias ->
                    SelectedCredential(
                        profileId = item.profileId,
                        alias = alias,
                    )
                }
            }
            .toList()
        if (enabledCredentials.isEmpty()) {
            return null
        }

        val normalizedCurrentIndex = cursorIndex.mod(enabledCredentials.size)
        val current = enabledCredentials[normalizedCurrentIndex]
        val nextIndex = (normalizedCurrentIndex + 1) % enabledCredentials.size
        val next = enabledCredentials[nextIndex]
        return CredentialRotation(
            currentIndex = normalizedCurrentIndex,
            current = current,
            nextIndex = nextIndex,
            next = next,
        )
    }

    private fun TaskExecutionResult.withCredentialContext(
        credentialSetId: String,
        credentialProfileId: String,
        credentialAlias: String,
    ): TaskExecutionResult = copy(
        taskRun = taskRun.copy(
            credentialSetId = credentialSetId,
            credentialProfileId = credentialProfileId,
            credentialAlias = credentialAlias,
        ),
    )

    private data class ParsedTask(
        val task: TaskDefinition,
    )

    private data class SelectedCredential(
        val profileId: String,
        val alias: String,
    )

    private data class CredentialRotation(
        val currentIndex: Int,
        val current: SelectedCredential,
        val nextIndex: Int,
        val next: SelectedCredential,
    )

    private data class ExecutableTask(
        val definitionRecord: TaskDefinitionRecord,
        val task: TaskDefinition,
        val scheduleState: TaskScheduleStateRecord,
    )
}
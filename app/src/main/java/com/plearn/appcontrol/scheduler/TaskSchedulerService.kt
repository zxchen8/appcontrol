package com.plearn.appcontrol.scheduler

import com.plearn.appcontrol.capability.CapabilityFacade
import com.plearn.appcontrol.capability.CapabilityResult
import com.plearn.appcontrol.capability.ScreenshotCaptureRequest
import com.plearn.appcontrol.data.model.ContinuousSessionRecord
import com.plearn.appcontrol.data.model.CredentialProfileRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskScheduleStateRecord
import com.plearn.appcontrol.data.repository.ContinuousSessionPersistenceRepository
import com.plearn.appcontrol.data.repository.TerminalSessionUpdate
import com.plearn.appcontrol.data.repository.CredentialRepository
import com.plearn.appcontrol.data.repository.SessionRepository
import com.plearn.appcontrol.data.repository.TaskRepository
import com.plearn.appcontrol.dsl.ConflictPolicy
import com.plearn.appcontrol.dsl.MissedSchedulePolicy
import com.plearn.appcontrol.dsl.TaskDefinition
import com.plearn.appcontrol.dsl.TaskDslParser
import com.plearn.appcontrol.dsl.TaskTrigger
import com.plearn.appcontrol.runner.AllowAllDiagnosticsArtifactCaptureGate
import com.plearn.appcontrol.runner.DiagnosticsArtifactCaptureGate
import com.plearn.appcontrol.runner.RunTriggerType
import com.plearn.appcontrol.runner.TaskExecutionRecorder
import com.plearn.appcontrol.runner.TaskExecutionResult
import com.plearn.appcontrol.runner.TaskRunStatus
import com.plearn.appcontrol.runner.TaskRunner
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.UUID

class TaskSchedulerService(
    private val parser: TaskDslParser,
    private val taskRepository: TaskRepository,
    private val credentialRepository: CredentialRepository,
    private val sessionRepository: SessionRepository,
    private val continuousSessionPersistenceRepository: ContinuousSessionPersistenceRepository? = null,
    private val taskRunner: TaskRunner,
    private val executionRecorder: TaskExecutionRecorder,
    private val capabilityFacade: CapabilityFacade? = null,
    private val diagnosticsArtifactCaptureGate: DiagnosticsArtifactCaptureGate = AllowAllDiagnosticsArtifactCaptureGate,
    private val timeSource: SchedulerTimeSource = SystemSchedulerTimeSource,
    private val cronScheduleCalculator: CronScheduleCalculator = CronScheduleCalculator(),
    private val executionLock: TaskExecutionLock = InMemoryTaskExecutionLock(),
    private val sessionIdFactory: () -> String,
    private val runIdFactory: () -> String = { UUID.randomUUID().toString() },
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

            if (executeWithLock(executableTask, nowMs, mode)) {
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

    private suspend fun executeWithLock(
        executableTask: ExecutableTask,
        nowMs: Long,
        mode: SchedulerDispatchMode,
    ): Boolean {
        val packageName = executableTask.task.targetApp.packageName
        if (!executionLock.tryAcquire(packageName)) {
            handleExecutionLockConflict(executableTask, nowMs)
            return false
        }

        return try {
            when (executableTask.task.trigger) {
                is TaskTrigger.Cron -> executeCronTask(executableTask, nowMs)
                is TaskTrigger.Continuous -> executeContinuousTask(executableTask, nowMs, mode)
            }
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

    private suspend fun executeCronTask(executableTask: ExecutableTask, nowMs: Long): Boolean {
        val trigger = executableTask.task.trigger as TaskTrigger.Cron
        val execution = try {
            taskRunner.run(executableTask.task, RunTriggerType.CRON)
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            val syntheticRunId = runIdFactory()
            executionRecorder.record(
                result = buildSyntheticCronRunResult(
                    runId = syntheticRunId,
                    taskId = executableTask.definitionRecord.taskId,
                    nowMs = nowMs,
                    errorCode = SchedulerFailureCode.SCHEDULER_EXECUTION_EXCEPTION,
                    message = error.message ?: "Cron task ${executableTask.definitionRecord.taskId} failed with an execution exception.",
                    artifactsJson = buildExecutionExceptionArtifactJson(
                        task = executableTask.task,
                        taskId = executableTask.definitionRecord.taskId,
                        runId = syntheticRunId,
                    ),
                ),
            )
            taskRepository.upsertScheduleState(
                executableTask.scheduleState.copy(
                    nextTriggerAt = cronScheduleCalculator.nextTriggerAt(trigger.expression, trigger.timezone, nowMs),
                    standbyEnabled = executableTask.definitionRecord.enabled,
                    lastTriggerAt = nowMs,
                    lastScheduleStatus = ScheduleStatus.BLOCKED,
                ),
            )
            return false
        }

        val persistedExecution = executionRecorder.record(result = execution)
        taskRepository.upsertScheduleState(
            executableTask.scheduleState.copy(
                nextTriggerAt = cronScheduleCalculator.nextTriggerAt(trigger.expression, trigger.timezone, nowMs),
                standbyEnabled = executableTask.definitionRecord.enabled,
                lastTriggerAt = nowMs,
                lastScheduleStatus = if (persistedExecution.taskRun.status == TaskRunStatus.BLOCKED) ScheduleStatus.BLOCKED else ScheduleStatus.SCHEDULED,
            ),
        )
        return true
    }

    private suspend fun executeContinuousTask(
        executableTask: ExecutableTask,
        nowMs: Long,
        mode: SchedulerDispatchMode,
    ): Boolean {
        val trigger = executableTask.task.trigger as TaskTrigger.Continuous
        val runningSession = sessionRepository.findRunningSession(executableTask.definitionRecord.taskId)
        val credentialSetId = executableTask.task.accountRotation.stringValue("credentialSetId") ?: run {
            blockContinuousTask(
                executableTask = executableTask,
                runningSession = runningSession,
                credentialSetId = null,
                nowMs = nowMs,
                errorCode = SchedulerFailureCode.SCHEDULER_CREDENTIAL_SET_UNAVAILABLE,
            )
            return false
        }
        val credentialSet = credentialRepository.getCredentialSet(credentialSetId)
        if (credentialSet == null || !credentialSet.enabled) {
            blockContinuousTask(
                executableTask = executableTask,
                runningSession = runningSession,
                credentialSetId = credentialSetId,
                nowMs = nowMs,
                errorCode = SchedulerFailureCode.SCHEDULER_CREDENTIAL_SET_UNAVAILABLE,
            )
            return false
        }

        val sessionCredentialSetChanged = runningSession?.credentialSetId != null &&
            runningSession.credentialSetId != credentialSetId

        val activeSession = when {
            runningSession == null -> ContinuousSessionRecord(
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

            sessionCredentialSetChanged -> runningSession.copy(
                credentialSetId = credentialSetId,
                currentCredentialProfileId = null,
                currentCredentialAlias = null,
                nextCredentialProfileId = null,
                nextCredentialAlias = null,
                cursorIndex = 0,
                lastErrorCode = null,
            )

            else -> runningSession
        }

        val persistCursor = executableTask.task.accountRotation.persistCursor()
        var sessionPersisted = runningSession != null
        return try {
            if (trigger.maxDurationMs != null && nowMs - activeSession.startedAt >= trigger.maxDurationMs) {
                if (!sessionPersisted && runningSession == null) {
                    sessionRepository.upsertSession(activeSession)
                    sessionPersisted = true
                }
                val terminalResult = executionRecorder.record(
                    result = buildSyntheticContinuousRunResult(
                    sessionId = activeSession.sessionId,
                    cycleNo = activeSession.totalCycles + 1,
                    taskId = executableTask.definitionRecord.taskId,
                    credentialSetId = credentialSetId,
                    nowMs = nowMs,
                    status = TaskRunStatus.TIMED_OUT,
                    errorCode = SchedulerFailureCode.SCHEDULER_MAX_DURATION_REACHED,
                    message = "Continuous task ${executableTask.definitionRecord.taskId} timed out before execution because the session reached maxDuration.",
                    artifactsJson = buildPreRunTimedOutArtifactJson(),
                ),
                    sessionId = activeSession.sessionId,
                    cycleNo = activeSession.totalCycles + 1,
                )
                val terminalUpdate = TerminalSessionUpdate(
                    sessionId = activeSession.sessionId,
                    status = TaskRunStatus.TIMED_OUT,
                    finishedAt = nowMs,
                    totalCycles = activeSession.totalCycles,
                    successCycles = activeSession.successCycles,
                    failedCycles = activeSession.failedCycles,
                    lastErrorCode = SchedulerFailureCode.SCHEDULER_MAX_DURATION_REACHED,
                )
                if (continuousSessionPersistenceRepository != null) {
                    continuousSessionPersistenceRepository.recordTerminalCycle(
                        taskRun = terminalResult.taskRun,
                        stepRuns = terminalResult.stepRuns,
                        terminalUpdate = terminalUpdate,
                    )
                } else {
                    sessionRepository.updateTerminalState(
                        sessionId = terminalUpdate.sessionId,
                        status = terminalUpdate.status,
                        finishedAt = terminalUpdate.finishedAt,
                        totalCycles = terminalUpdate.totalCycles,
                        successCycles = terminalUpdate.successCycles,
                        failedCycles = terminalUpdate.failedCycles,
                        lastErrorCode = terminalUpdate.lastErrorCode,
                    )
                }
                taskRepository.upsertScheduleState(
                    executableTask.scheduleState.copy(
                        nextTriggerAt = null,
                        standbyEnabled = false,
                        lastTriggerAt = nowMs,
                        lastScheduleStatus = ScheduleStatus.SCHEDULED,
                    ),
                )
                return false
            }

            val credentialAliasByProfileId = credentialRepository.getEnabledProfiles()
                .associateBy(CredentialProfileRecord::profileId, CredentialProfileRecord::alias)
            val resumedCursorIndex = if (!persistCursor && mode == SchedulerDispatchMode.RECOVERY) {
                0
            } else {
                activeSession.cursorIndex
            }
            val rotation = resolveCredentialRotation(
                credentialSet = credentialSet,
                aliasByProfileId = credentialAliasByProfileId,
                cursorIndex = resumedCursorIndex,
                resumeProfileId = if (!persistCursor && mode == SchedulerDispatchMode.RECOVERY) {
                    null
                } else {
                    activeSession.nextCredentialProfileId
                },
            ) ?: run {
                blockContinuousTask(
                    executableTask = executableTask,
                    runningSession = activeSession,
                    credentialSetId = credentialSetId,
                    nowMs = nowMs,
                    errorCode = SchedulerFailureCode.SCHEDULER_CREDENTIAL_SET_UNAVAILABLE,
                )
                return false
            }
            val stopSessionOnFailure = executableTask.task.accountRotation.onCycleFailureStopSession()

            if (runningSession == null || sessionCredentialSetChanged) {
                sessionRepository.upsertSession(activeSession)
                sessionPersisted = true
            }

            val cycleNo = activeSession.totalCycles + 1
            val rawExecution = taskRunner.run(executableTask.task, RunTriggerType.CONTINUOUS).withCredentialContext(
                credentialSetId = credentialSetId,
                credentialProfileId = rotation.current.profileId,
                credentialAlias = rotation.current.alias,
            )
            val execution = if (continuousSessionPersistenceRepository != null) {
                rawExecution.withSessionContext(
                    sessionId = activeSession.sessionId,
                    cycleNo = cycleNo,
                )
            } else {
                executionRecorder.record(
                    result = rawExecution,
                    sessionId = activeSession.sessionId,
                    cycleNo = cycleNo,
                )
            }

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
                val terminalUpdate = TerminalSessionUpdate(
                    sessionId = activeSession.sessionId,
                    status = terminalStatus,
                    finishedAt = nowMs,
                    totalCycles = totalCycles,
                    successCycles = successCycles,
                    failedCycles = failedCycles,
                    lastErrorCode = execution.taskRun.errorCode,
                )
                if (continuousSessionPersistenceRepository != null) {
                    continuousSessionPersistenceRepository.recordTerminalCycle(
                        taskRun = execution.taskRun,
                        stepRuns = execution.stepRuns,
                        terminalUpdate = terminalUpdate,
                    )
                } else {
                    sessionRepository.updateTerminalState(
                        sessionId = terminalUpdate.sessionId,
                        status = terminalUpdate.status,
                        finishedAt = terminalUpdate.finishedAt,
                        totalCycles = terminalUpdate.totalCycles,
                        successCycles = terminalUpdate.successCycles,
                        failedCycles = terminalUpdate.failedCycles,
                        lastErrorCode = terminalUpdate.lastErrorCode,
                    )
                }
                taskRepository.upsertScheduleState(
                    executableTask.scheduleState.copy(
                        nextTriggerAt = null,
                        standbyEnabled = false,
                        lastTriggerAt = nowMs,
                        lastScheduleStatus = if (terminalStatus == TaskRunStatus.BLOCKED) ScheduleStatus.BLOCKED else ScheduleStatus.SCHEDULED,
                    ),
                )
                return true
            }

            val updatedSession = activeSession.copy(
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
            )
            continuousSessionPersistenceRepository?.recordCycleProgress(
                taskRun = execution.taskRun,
                stepRuns = execution.stepRuns,
                session = updatedSession,
            )
            if (continuousSessionPersistenceRepository == null) {
                sessionRepository.upsertSession(updatedSession)
            }
            taskRepository.upsertScheduleState(
                executableTask.scheduleState.copy(
                    nextTriggerAt = nowMs + trigger.cooldownMs,
                    standbyEnabled = executableTask.definitionRecord.enabled,
                    lastTriggerAt = nowMs,
                    lastScheduleStatus = ScheduleStatus.SCHEDULED,
                ),
            )
            true
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            if (sessionPersisted) {
                val syntheticRunId = runIdFactory()
                val terminalResult = buildSyntheticContinuousRunResult(
                    runId = syntheticRunId,
                    sessionId = activeSession.sessionId,
                    cycleNo = activeSession.totalCycles + 1,
                    taskId = executableTask.definitionRecord.taskId,
                    credentialSetId = credentialSetId,
                    nowMs = nowMs,
                    status = TaskRunStatus.BLOCKED,
                    errorCode = SchedulerFailureCode.SCHEDULER_EXECUTION_EXCEPTION,
                    message = error.message ?: "Continuous task ${executableTask.definitionRecord.taskId} failed with an execution exception.",
                    artifactsJson = buildExecutionExceptionArtifactJson(
                        task = executableTask.task,
                        taskId = executableTask.definitionRecord.taskId,
                        runId = syntheticRunId,
                    ),
                )
                val terminalUpdate = TerminalSessionUpdate(
                    sessionId = activeSession.sessionId,
                    status = TaskRunStatus.BLOCKED,
                    finishedAt = nowMs,
                    totalCycles = activeSession.totalCycles,
                    successCycles = activeSession.successCycles,
                    failedCycles = activeSession.failedCycles,
                    lastErrorCode = SchedulerFailureCode.SCHEDULER_EXECUTION_EXCEPTION,
                )
                if (continuousSessionPersistenceRepository != null) {
                    continuousSessionPersistenceRepository.recordTerminalCycle(
                        taskRun = terminalResult.taskRun,
                        stepRuns = terminalResult.stepRuns,
                        terminalUpdate = terminalUpdate,
                    )
                } else {
                    executionRecorder.record(
                        result = terminalResult,
                        sessionId = activeSession.sessionId,
                        cycleNo = activeSession.totalCycles + 1,
                    )
                    sessionRepository.updateTerminalState(
                        sessionId = terminalUpdate.sessionId,
                        status = terminalUpdate.status,
                        finishedAt = terminalUpdate.finishedAt,
                        totalCycles = terminalUpdate.totalCycles,
                        successCycles = terminalUpdate.successCycles,
                        failedCycles = terminalUpdate.failedCycles,
                        lastErrorCode = terminalUpdate.lastErrorCode,
                    )
                }
            }
            taskRepository.upsertScheduleState(
                executableTask.scheduleState.copy(
                    nextTriggerAt = null,
                    standbyEnabled = false,
                    lastTriggerAt = nowMs,
                    lastScheduleStatus = ScheduleStatus.BLOCKED,
                ),
            )
            false
        }
    }

    private suspend fun blockContinuousTask(
        executableTask: ExecutableTask,
        runningSession: ContinuousSessionRecord?,
        credentialSetId: String?,
        nowMs: Long,
        errorCode: String,
    ) {
        val session = ensureSessionForPreRunFailure(
            runningSession = runningSession,
            taskId = executableTask.definitionRecord.taskId,
            credentialSetId = credentialSetId,
            nowMs = nowMs,
        )
        val terminalResult = buildSyntheticContinuousRunResult(
            sessionId = session.sessionId,
            cycleNo = session.totalCycles + 1,
            taskId = executableTask.definitionRecord.taskId,
            credentialSetId = credentialSetId,
            nowMs = nowMs,
            status = TaskRunStatus.BLOCKED,
            errorCode = errorCode,
            message = "Continuous task ${executableTask.definitionRecord.taskId} blocked before execution.",
            artifactsJson = buildPreRunBlockedArtifactJson(),
        )
        val terminalUpdate = TerminalSessionUpdate(
            sessionId = session.sessionId,
            status = TaskRunStatus.BLOCKED,
            finishedAt = nowMs,
            totalCycles = session.totalCycles,
            successCycles = session.successCycles,
            failedCycles = session.failedCycles,
            lastErrorCode = errorCode,
        )
        if (continuousSessionPersistenceRepository != null) {
            continuousSessionPersistenceRepository.recordTerminalCycle(
                taskRun = terminalResult.taskRun,
                stepRuns = terminalResult.stepRuns,
                terminalUpdate = terminalUpdate,
            )
        } else {
            executionRecorder.record(
                result = terminalResult,
                sessionId = session.sessionId,
                cycleNo = session.totalCycles + 1,
            )
            sessionRepository.updateTerminalState(
                sessionId = terminalUpdate.sessionId,
                status = terminalUpdate.status,
                finishedAt = terminalUpdate.finishedAt,
                totalCycles = terminalUpdate.totalCycles,
                successCycles = terminalUpdate.successCycles,
                failedCycles = terminalUpdate.failedCycles,
                lastErrorCode = terminalUpdate.lastErrorCode,
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

    private fun buildSyntheticContinuousRunResult(
        runId: String = runIdFactory(),
        sessionId: String,
        cycleNo: Int,
        taskId: String,
        credentialSetId: String?,
        nowMs: Long,
        status: String,
        errorCode: String,
        message: String,
        artifactsJson: String,
    ): TaskExecutionResult = TaskExecutionResult(
        taskRun = TaskRunRecord(
            runId = runId,
            sessionId = sessionId,
            cycleNo = cycleNo,
            taskId = taskId,
            credentialSetId = credentialSetId,
            credentialProfileId = null,
            credentialAlias = null,
            status = status,
            startedAt = nowMs,
            finishedAt = nowMs,
            durationMs = 0L,
            triggerType = RunTriggerType.CONTINUOUS,
            errorCode = errorCode,
            message = message,
            artifactsJson = artifactsJson,
        ),
        stepRuns = emptyList(),
        taskAttemptCount = 0,
    )

    private fun TaskExecutionResult.withSessionContext(
        sessionId: String,
        cycleNo: Int,
    ): TaskExecutionResult {
        val persistedTaskRun = taskRun.copy(
            sessionId = sessionId,
            cycleNo = cycleNo,
        )
        return copy(
            taskRun = persistedTaskRun,
            stepRuns = stepRuns.map { stepRun ->
                stepRun.copy(runId = persistedTaskRun.runId)
            },
        )
    }

    private fun buildSyntheticCronRunResult(
        runId: String = runIdFactory(),
        taskId: String,
        nowMs: Long,
        errorCode: String,
        message: String,
        artifactsJson: String,
    ): TaskExecutionResult = TaskExecutionResult(
        taskRun = TaskRunRecord(
            runId = runId,
            sessionId = null,
            cycleNo = null,
            taskId = taskId,
            credentialSetId = null,
            credentialProfileId = null,
            credentialAlias = null,
            status = TaskRunStatus.BLOCKED,
            startedAt = nowMs,
            finishedAt = nowMs,
            durationMs = 0L,
            triggerType = RunTriggerType.CRON,
            errorCode = errorCode,
            message = message,
            artifactsJson = artifactsJson,
        ),
        stepRuns = emptyList(),
        taskAttemptCount = 0,
    )

    private fun buildPreRunBlockedArtifactJson(): String = buildDiagnosticArtifactJson(
        artifactType = "screenshot_skipped",
        reason = PRE_RUN_BLOCKED_ARTIFACT_REASON,
        captureRequested = false,
        sensitiveContextActive = false,
    )

    private fun buildPreRunTimedOutArtifactJson(): String = buildDiagnosticArtifactJson(
        artifactType = "screenshot_skipped",
        reason = PRE_RUN_TIMEOUT_ARTIFACT_REASON,
        captureRequested = false,
        sensitiveContextActive = false,
    )

    private suspend fun buildExecutionExceptionArtifactJson(
        task: TaskDefinition,
        taskId: String,
        runId: String,
    ): String = when {
        !task.diagnostics.captureScreenshotOnFailure -> buildDiagnosticArtifactJson(
            artifactType = "screenshot_skipped",
            reason = SCREENSHOT_CAPTURE_DISABLED_BY_POLICY,
            captureRequested = false,
            sensitiveContextActive = false,
        )

        !diagnosticsArtifactCaptureGate.canCaptureFailureArtifact(taskId = taskId, runId = runId) -> buildDiagnosticArtifactJson(
            artifactType = "screenshot_skipped",
            reason = ARTIFACT_STORAGE_LIMIT_REACHED,
            captureRequested = true,
            sensitiveContextActive = false,
        )

        else -> when (
            val captureResult = capabilityFacade?.captureScreenshot(
                ScreenshotCaptureRequest(
                    taskId = taskId,
                    runId = runId,
                ),
            ) ?: CapabilityResult.Failure(
                errorCode = "STEP_CAPABILITY_UNAVAILABLE",
                message = "Screenshot capability is not configured.",
            )
        ) {
            is CapabilityResult.Success -> buildDiagnosticArtifactJson(
                artifactType = "screenshot",
                reason = null,
                captureRequested = true,
                sensitiveContextActive = false,
                relativePath = captureResult.value.relativePath,
                mimeType = captureResult.value.mimeType,
                fileSizeBytes = captureResult.value.fileSizeBytes,
            )

            is CapabilityResult.Failure -> buildDiagnosticArtifactJson(
                artifactType = "screenshot_unavailable",
                reason = SCREENSHOT_CAPTURE_FAILED,
                captureRequested = true,
                sensitiveContextActive = false,
            )
        }
    }

    private fun buildDiagnosticArtifactJson(
        artifactType: String,
        reason: String?,
        captureRequested: Boolean,
        sensitiveContextActive: Boolean,
        relativePath: String? = null,
        mimeType: String? = null,
        fileSizeBytes: Long? = null,
    ): String = buildJsonObject {
        put("artifactType", artifactType)
        reason?.let { put("reason", it) }
        put("captureRequested", captureRequested)
        put("sensitiveContextActive", sensitiveContextActive)
        relativePath?.let { put("relativePath", it) }
        mimeType?.let { put("mimeType", it) }
        fileSizeBytes?.let { put("fileSizeBytes", it) }
    }.toString()

    private suspend fun ensureSessionForPreRunFailure(
        runningSession: ContinuousSessionRecord?,
        taskId: String,
        credentialSetId: String?,
        nowMs: Long,
    ): ContinuousSessionRecord {
        if (runningSession != null) {
            return runningSession
        }
        val session = ContinuousSessionRecord(
            sessionId = sessionIdFactory(),
            taskId = taskId,
            credentialSetId = credentialSetId.orEmpty(),
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
        sessionRepository.upsertSession(session)
        return session
    }

    private fun parseExecutableTask(definitionRecord: TaskDefinitionRecord): ParsedTask? {
        val parseResult = parser.parse(definitionRecord.rawJson)
        val task = parseResult.task ?: return null
        return ParsedTask(task = task)
    }

    private companion object {
        const val PRE_RUN_BLOCKED_ARTIFACT_REASON = "DIAG_SCREENSHOT_NOT_CAPTURED_BEFORE_FIRST_ACTION_BLOCK"
        const val PRE_RUN_TIMEOUT_ARTIFACT_REASON = "DIAG_SCREENSHOT_NOT_CAPTURED_BEFORE_EXECUTION_TIMEOUT"
        const val SCREENSHOT_CAPTURE_DISABLED_BY_POLICY = "DIAG_SCREENSHOT_CAPTURE_DISABLED_BY_POLICY"
        const val ARTIFACT_STORAGE_LIMIT_REACHED = "DIAG_ARTIFACT_STORAGE_LIMIT_REACHED"
        const val SCREENSHOT_CAPTURE_FAILED = "DIAG_SCREENSHOT_CAPTURE_FAILED"
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

    private fun JsonObject?.persistCursor(): Boolean =
        stringValue("persistCursor")?.equals("false", ignoreCase = true) != true

    private fun resolveCredentialRotation(
        credentialSet: com.plearn.appcontrol.data.model.CredentialSetRecord,
        aliasByProfileId: Map<String, String>,
        cursorIndex: Int,
        resumeProfileId: String?,
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

        val normalizedCurrentIndex = resumeProfileId
            ?.let { profileId ->
                enabledCredentials.indexOfFirst { credential -> credential.profileId == profileId }
                    .takeIf { index -> index >= 0 }
            }
            ?: cursorIndex.mod(enabledCredentials.size)
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

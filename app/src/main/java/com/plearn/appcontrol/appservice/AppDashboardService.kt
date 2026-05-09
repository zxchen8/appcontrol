package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.data.model.ContinuousSessionRecord
import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.data.repository.RunRecordRepository
import com.plearn.appcontrol.data.repository.SessionRepository
import com.plearn.appcontrol.data.repository.TaskRepository
import javax.inject.Inject

data class AppDashboardSnapshot(
    val tasks: List<TaskDashboardItem>,
    val runningSessions: List<RunningSessionSummary>,
    val recentRuns: List<RecentRunSummary>,
)

data class TaskDashboardItem(
    val taskId: String,
    val name: String,
    val enabled: Boolean,
    val definitionStatus: String,
    val triggerType: String,
    val nextTriggerAt: Long?,
    val standbyEnabled: Boolean,
    val latestRunStatus: String?,
    val latestRunStartedAt: Long?,
    val latestRunTriggerType: String?,
    val latestRunErrorCode: String?,
    val latestRunMessage: String?,
    val runningSession: RunningSessionSummary?,
)

data class RunningSessionSummary(
    val sessionId: String,
    val taskId: String,
    val taskName: String,
    val startedAt: Long,
    val totalCycles: Int,
    val successCycles: Int,
    val failedCycles: Int,
    val currentCredentialAlias: String?,
    val nextCredentialAlias: String?,
    val lastErrorCode: String?,
)

data class RecentRunSummary(
    val runId: String,
    val taskId: String,
    val taskName: String,
    val sessionId: String?,
    val cycleNo: Int?,
    val triggerType: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val credentialAlias: String?,
    val errorCode: String?,
    val message: String? = null,
    val artifactsJson: String = "{}",
)

class AppDashboardService @Inject constructor(
    private val taskRepository: TaskRepository,
    private val runRecordRepository: RunRecordRepository,
    private val sessionRepository: SessionRepository,
) {
    suspend fun loadSnapshot(recentRunLimit: Int = DEFAULT_RECENT_RUN_LIMIT): AppDashboardSnapshot {
        val definitions = taskRepository.listTaskDefinitions()
        val definitionByTaskId = definitions.associateBy(TaskDefinitionRecord::taskId)
        val runningSessions = sessionRepository.findRunningSessions()
            .map { session -> session.toSummary(definitionByTaskId) }
        val runningSessionByTaskId = runningSessions.associateBy(RunningSessionSummary::taskId)

        val tasks = definitions.map { definition ->
            val scheduleState = taskRepository.getScheduleState(definition.taskId)
            val latestRun = runRecordRepository.findLatestTaskRun(definition.taskId)
            TaskDashboardItem(
                taskId = definition.taskId,
                name = definition.name,
                enabled = definition.enabled,
                definitionStatus = definition.definitionStatus,
                triggerType = definition.triggerType,
                nextTriggerAt = scheduleState?.nextTriggerAt,
                standbyEnabled = scheduleState?.standbyEnabled ?: false,
                latestRunStatus = latestRun?.status,
                latestRunStartedAt = latestRun?.startedAt,
                latestRunTriggerType = latestRun?.triggerType,
                latestRunErrorCode = latestRun?.errorCode,
                latestRunMessage = latestRun?.message,
                runningSession = runningSessionByTaskId[definition.taskId],
            )
        }

        val recentRuns = runRecordRepository.listRecentTaskRuns(recentRunLimit)
            .map { run -> run.toSummary(definitionByTaskId) }

        return AppDashboardSnapshot(
            tasks = tasks,
            runningSessions = runningSessions,
            recentRuns = recentRuns,
        )
    }

    private fun ContinuousSessionRecord.toSummary(
        definitionByTaskId: Map<String, TaskDefinitionRecord>,
    ): RunningSessionSummary = RunningSessionSummary(
        sessionId = sessionId,
        taskId = taskId,
        taskName = definitionByTaskId[taskId]?.name ?: taskId,
        startedAt = startedAt,
        totalCycles = totalCycles,
        successCycles = successCycles,
        failedCycles = failedCycles,
        currentCredentialAlias = currentCredentialAlias,
        nextCredentialAlias = nextCredentialAlias,
        lastErrorCode = lastErrorCode,
    )

    private fun TaskRunRecord.toSummary(
        definitionByTaskId: Map<String, TaskDefinitionRecord>,
    ): RecentRunSummary = RecentRunSummary(
        runId = runId,
        taskId = taskId,
        taskName = definitionByTaskId[taskId]?.name ?: taskId,
        sessionId = sessionId,
        cycleNo = cycleNo,
        triggerType = triggerType,
        status = status,
        startedAt = startedAt,
        finishedAt = finishedAt,
        credentialAlias = credentialAlias,
        errorCode = errorCode,
        message = message,
        artifactsJson = artifactsJson,
    )

    private companion object {
        const val DEFAULT_RECENT_RUN_LIMIT = 10
    }
}
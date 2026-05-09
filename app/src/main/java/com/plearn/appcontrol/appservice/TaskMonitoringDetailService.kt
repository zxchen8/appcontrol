package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskScheduleStateRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.data.repository.RunRecordRepository
import com.plearn.appcontrol.data.repository.SessionRepository
import com.plearn.appcontrol.data.repository.TaskRepository
import javax.inject.Inject

data class TaskMonitoringDetailSnapshot(
    val definition: TaskDefinitionRecord,
    val scheduleState: TaskScheduleStateRecord?,
    val runningSession: RunningSessionSummary?,
    val recentRuns: List<RecentRunSummary>,
    val selectedRun: RecentRunSummary?,
    val stepRuns: List<StepRunRecord>,
)

class TaskMonitoringDetailService @Inject constructor(
    private val taskRepository: TaskRepository,
    private val runRecordRepository: RunRecordRepository,
    private val sessionRepository: SessionRepository,
) {
    suspend fun loadTaskDetail(
        taskId: String,
        selectedRunId: String? = null,
        recentRunLimit: Int = DEFAULT_RECENT_RUN_LIMIT,
    ): TaskMonitoringDetailSnapshot? {
        val normalizedRecentRunLimit = recentRunLimit.coerceAtLeast(0)
        val definition = taskRepository.getTaskDefinition(taskId) ?: return null
        val scheduleState = taskRepository.getScheduleState(taskId)
        val runningSession = sessionRepository.findRunningSession(taskId)?.toSummary(definition)
        val recentRuns = runRecordRepository.listRecentTaskRunsByTaskId(taskId, normalizedRecentRunLimit)
            .map { run -> run.toSummary(definition) }
        val selectedRun = recentRuns.firstOrNull { run -> run.runId == selectedRunId } ?: recentRuns.firstOrNull()
        val stepRuns = if (selectedRun == null) {
            emptyList()
        } else {
            runRecordRepository.findStepRuns(selectedRun.runId)
        }

        return TaskMonitoringDetailSnapshot(
            definition = definition,
            scheduleState = scheduleState,
            runningSession = runningSession,
            recentRuns = recentRuns,
            selectedRun = selectedRun,
            stepRuns = stepRuns,
        )
    }

    private fun com.plearn.appcontrol.data.model.ContinuousSessionRecord.toSummary(
        definition: TaskDefinitionRecord,
    ): RunningSessionSummary = RunningSessionSummary(
        sessionId = sessionId,
        taskId = taskId,
        taskName = definition.name,
        startedAt = startedAt,
        totalCycles = totalCycles,
        successCycles = successCycles,
        failedCycles = failedCycles,
        currentCredentialAlias = currentCredentialAlias,
        nextCredentialAlias = nextCredentialAlias,
        lastErrorCode = lastErrorCode,
    )

    private fun TaskRunRecord.toSummary(definition: TaskDefinitionRecord): RecentRunSummary = RecentRunSummary(
        runId = runId,
        taskId = taskId,
        taskName = definition.name,
        sessionId = sessionId,
        cycleNo = cycleNo,
        triggerType = triggerType,
        status = status,
        startedAt = startedAt,
        finishedAt = finishedAt,
        credentialAlias = credentialAlias,
        errorCode = errorCode,
    )

    private companion object {
        const val DEFAULT_RECENT_RUN_LIMIT = 10
    }
}
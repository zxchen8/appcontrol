package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskScheduleStateRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.data.repository.RunRecordRepository
import com.plearn.appcontrol.data.repository.SessionRepository
import com.plearn.appcontrol.data.repository.TaskRepository
import com.plearn.appcontrol.diagnostics.toDiagnosticArtifactDisplayText
import com.plearn.appcontrol.dsl.DiagnosticsPolicy
import com.plearn.appcontrol.dsl.TaskDslParser
import javax.inject.Inject

data class TaskDiagnosticsSummary(
    val captureScreenshotOnFailure: Boolean,
    val captureScreenshotOnStepFailure: Boolean,
    val logLevel: String,
)

data class TaskFailureContextSummary(
    val runId: String,
    val status: String,
    val runErrorCode: String?,
    val runMessage: String?,
    val failedStepCount: Int,
    val primaryFailedStepId: String?,
    val primaryFailedStepErrorCode: String?,
    val primaryFailedStepMessage: String?,
    val stepArtifacts: List<String>,
)

data class TaskMonitoringDetailSnapshot(
    val definition: TaskDefinitionRecord,
    val diagnostics: TaskDiagnosticsSummary,
    val scheduleState: TaskScheduleStateRecord?,
    val runningSession: RunningSessionSummary?,
    val recentRuns: List<RecentRunSummary>,
    val selectedRun: RecentRunSummary?,
    val failureContext: TaskFailureContextSummary?,
    val stepRuns: List<StepRunRecord>,
)

class TaskMonitoringDetailService @Inject constructor(
    private val taskRepository: TaskRepository,
    private val runRecordRepository: RunRecordRepository,
    private val sessionRepository: SessionRepository,
    private val parser: TaskDslParser = TaskDslParser(),
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
        val diagnostics = parser.parse(definition.rawJson).task?.diagnostics?.toSummary() ?: DiagnosticsPolicy().toSummary()
        val failureContext = buildFailureContext(selectedRun, stepRuns)

        return TaskMonitoringDetailSnapshot(
            definition = definition,
            diagnostics = diagnostics,
            scheduleState = scheduleState,
            runningSession = runningSession,
            recentRuns = recentRuns,
            selectedRun = selectedRun,
            failureContext = failureContext,
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
        message = message,
    )

    private fun DiagnosticsPolicy.toSummary(): TaskDiagnosticsSummary = TaskDiagnosticsSummary(
        captureScreenshotOnFailure = captureScreenshotOnFailure,
        captureScreenshotOnStepFailure = captureScreenshotOnStepFailure,
        logLevel = logLevel,
    )

    private fun buildFailureContext(
        selectedRun: RecentRunSummary?,
        stepRuns: List<StepRunRecord>,
    ): TaskFailureContextSummary? {
        if (selectedRun == null) {
            return null
        }

        val runLooksFailed = selectedRun.status.equals("failed", ignoreCase = true) ||
            selectedRun.status.equals("blocked", ignoreCase = true) ||
            selectedRun.status.equals("timed_out", ignoreCase = true)
        if (!runLooksFailed) {
            return null
        }

        val finalAttemptByStepId = linkedMapOf<String, StepRunRecord>()
        stepRuns.forEach { step ->
            finalAttemptByStepId[step.stepId] = step
        }
        val failedSteps = finalAttemptByStepId.values.filter { step ->
            !step.status.equals("success", ignoreCase = true) || step.errorCode != null
        }

        val primaryFailedStep = failedSteps.firstOrNull { step ->
            step.errorCode != null || !step.message.isNullOrBlank()
        } ?: failedSteps.firstOrNull()

        return TaskFailureContextSummary(
            runId = selectedRun.runId,
            status = selectedRun.status,
            runErrorCode = selectedRun.errorCode,
            runMessage = selectedRun.message,
            failedStepCount = failedSteps.size,
            primaryFailedStepId = primaryFailedStep?.stepId,
            primaryFailedStepErrorCode = primaryFailedStep?.errorCode,
            primaryFailedStepMessage = primaryFailedStep?.message,
            stepArtifacts = failedSteps.mapNotNull { step ->
                step.artifactsJson.toArtifactSummary(step.stepId)
            }.distinct(),
        )
    }

    private fun String.toArtifactSummary(stepId: String): String? {
        val summary = toDiagnosticArtifactDisplayText() ?: return null
        return "$stepId=$summary"
    }

    private companion object {
        const val DEFAULT_RECENT_RUN_LIMIT = 10
    }
}
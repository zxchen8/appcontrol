package com.plearn.appcontrol.runner

import com.plearn.appcontrol.data.repository.RunRecordRepository

interface TaskExecutionRecorder {
    suspend fun record(
        result: TaskExecutionResult,
        sessionId: String? = null,
        cycleNo: Int? = null,
    ): TaskExecutionResult
}

object NoOpTaskExecutionRecorder : TaskExecutionRecorder {
    override suspend fun record(
        result: TaskExecutionResult,
        sessionId: String?,
        cycleNo: Int?,
    ): TaskExecutionResult = result
}

class RepositoryBackedTaskExecutionRecorder(
    private val runRecordRepository: RunRecordRepository,
) : TaskExecutionRecorder {
    override suspend fun record(
        result: TaskExecutionResult,
        sessionId: String?,
        cycleNo: Int?,
    ): TaskExecutionResult {
        val persistedTaskRun = result.taskRun.copy(
            sessionId = sessionId ?: result.taskRun.sessionId,
            cycleNo = cycleNo ?: result.taskRun.cycleNo,
        )
        val persistedStepRuns = result.stepRuns.map { stepRun ->
            stepRun.copy(runId = persistedTaskRun.runId)
        }

        runRecordRepository.upsertTaskRun(persistedTaskRun)
        if (persistedStepRuns.isNotEmpty()) {
            runRecordRepository.insertStepRuns(persistedStepRuns)
        }

        return result.copy(
            taskRun = persistedTaskRun,
            stepRuns = persistedStepRuns,
        )
    }
}
package com.plearn.appcontrol.data.repository

import com.plearn.appcontrol.data.local.dao.StepRunDao
import com.plearn.appcontrol.data.local.dao.TaskRunDao
import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.data.model.TaskRunRecord

interface RunRecordRepository {
    suspend fun upsertTaskRun(taskRun: TaskRunRecord)
    suspend fun findLatestTaskRun(taskId: String): TaskRunRecord?
    suspend fun findTaskRunsBySession(sessionId: String): List<TaskRunRecord>
    suspend fun insertStepRuns(stepRuns: List<StepRunRecord>)
    suspend fun findStepRuns(runId: String): List<StepRunRecord>
}

class RoomRunRecordRepository(
    private val taskRunDao: TaskRunDao,
    private val stepRunDao: StepRunDao,
) : RunRecordRepository {
    override suspend fun upsertTaskRun(taskRun: TaskRunRecord) {
        taskRunDao.upsert(taskRun.toEntity())
    }

    override suspend fun findLatestTaskRun(taskId: String): TaskRunRecord? =
        taskRunDao.findLatestByTaskId(taskId)?.toRecord()

    override suspend fun findTaskRunsBySession(sessionId: String): List<TaskRunRecord> =
        taskRunDao.findBySessionId(sessionId).map { it.toRecord() }

    override suspend fun insertStepRuns(stepRuns: List<StepRunRecord>) {
        stepRunDao.insertAll(stepRuns.map { it.toEntity() })
    }

    override suspend fun findStepRuns(runId: String): List<StepRunRecord> =
        stepRunDao.findByRunId(runId).map { it.toRecord() }
}
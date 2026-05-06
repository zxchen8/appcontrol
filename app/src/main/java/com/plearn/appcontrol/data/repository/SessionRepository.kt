package com.plearn.appcontrol.data.repository

import com.plearn.appcontrol.data.local.dao.ContinuousSessionDao
import com.plearn.appcontrol.data.model.ContinuousSessionRecord

interface SessionRepository {
    suspend fun upsertSession(session: ContinuousSessionRecord)
    suspend fun findRunningSession(taskId: String): ContinuousSessionRecord?
    suspend fun updateTerminalState(
        sessionId: String,
        status: String,
        finishedAt: Long?,
        totalCycles: Int,
        successCycles: Int,
        failedCycles: Int,
        lastErrorCode: String?,
    )
}

class RoomSessionRepository(
    private val continuousSessionDao: ContinuousSessionDao,
) : SessionRepository {
    override suspend fun upsertSession(session: ContinuousSessionRecord) {
        continuousSessionDao.upsert(session.toEntity())
    }

    override suspend fun findRunningSession(taskId: String): ContinuousSessionRecord? =
        continuousSessionDao.findRunningSessionByTaskId(taskId)?.toRecord()

    override suspend fun updateTerminalState(
        sessionId: String,
        status: String,
        finishedAt: Long?,
        totalCycles: Int,
        successCycles: Int,
        failedCycles: Int,
        lastErrorCode: String?,
    ) {
        continuousSessionDao.updateTerminalState(
            sessionId = sessionId,
            status = status,
            finishedAt = finishedAt,
            totalCycles = totalCycles,
            successCycles = successCycles,
            failedCycles = failedCycles,
            lastErrorCode = lastErrorCode,
        )
    }
}
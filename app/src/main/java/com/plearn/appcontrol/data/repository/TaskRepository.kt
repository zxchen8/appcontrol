package com.plearn.appcontrol.data.repository

import com.plearn.appcontrol.data.local.dao.TaskDefinitionDao
import com.plearn.appcontrol.data.local.dao.TaskScheduleStateDao
import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskScheduleStateRecord

interface TaskRepository {
    suspend fun listTaskDefinitions(): List<TaskDefinitionRecord>
    suspend fun getTaskDefinition(taskId: String): TaskDefinitionRecord?
    suspend fun upsertTaskDefinition(taskDefinition: TaskDefinitionRecord)
    suspend fun updateDefinitionStatus(taskId: String, definitionStatus: String, updatedAt: Long)
    suspend fun updateEnabled(taskId: String, enabled: Boolean, updatedAt: Long)
    suspend fun getScheduleState(taskId: String): TaskScheduleStateRecord?
    suspend fun upsertScheduleState(taskScheduleState: TaskScheduleStateRecord)
}

class RoomTaskRepository(
    private val taskDefinitionDao: TaskDefinitionDao,
    private val taskScheduleStateDao: TaskScheduleStateDao,
) : TaskRepository {
    override suspend fun listTaskDefinitions(): List<TaskDefinitionRecord> =
        taskDefinitionDao.getAll().map { it.toRecord() }

    override suspend fun getTaskDefinition(taskId: String): TaskDefinitionRecord? =
        taskDefinitionDao.getByTaskId(taskId)?.toRecord()

    override suspend fun upsertTaskDefinition(taskDefinition: TaskDefinitionRecord) {
        taskDefinitionDao.upsert(taskDefinition.toEntity())
    }

    override suspend fun updateDefinitionStatus(taskId: String, definitionStatus: String, updatedAt: Long) {
        taskDefinitionDao.updateDefinitionStatus(taskId, definitionStatus, updatedAt)
    }

    override suspend fun updateEnabled(taskId: String, enabled: Boolean, updatedAt: Long) {
        taskDefinitionDao.updateEnabled(taskId, enabled, updatedAt)
    }

    override suspend fun getScheduleState(taskId: String): TaskScheduleStateRecord? =
        taskScheduleStateDao.getByTaskId(taskId)?.toRecord()

    override suspend fun upsertScheduleState(taskScheduleState: TaskScheduleStateRecord) {
        taskScheduleStateDao.upsert(taskScheduleState.toEntity())
    }
}
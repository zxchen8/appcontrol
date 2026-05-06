package com.plearn.appcontrol.data.repository

import com.plearn.appcontrol.data.local.entity.ContinuousSessionEntity
import com.plearn.appcontrol.data.local.entity.CredentialProfileEntity
import com.plearn.appcontrol.data.local.entity.CredentialSecretEntity
import com.plearn.appcontrol.data.local.entity.CredentialSetEntity
import com.plearn.appcontrol.data.local.entity.CredentialSetItemEntity
import com.plearn.appcontrol.data.local.entity.CredentialSetWithItems
import com.plearn.appcontrol.data.local.entity.StepRunEntity
import com.plearn.appcontrol.data.local.entity.TaskDefinitionEntity
import com.plearn.appcontrol.data.local.entity.TaskRunEntity
import com.plearn.appcontrol.data.local.entity.TaskScheduleStateEntity
import com.plearn.appcontrol.data.model.ContinuousSessionRecord
import com.plearn.appcontrol.data.model.CredentialProfileRecord
import com.plearn.appcontrol.data.model.CredentialSecretRecord
import com.plearn.appcontrol.data.model.CredentialSetItemRecord
import com.plearn.appcontrol.data.model.CredentialSetRecord
import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.data.model.TaskScheduleStateRecord

internal fun TaskDefinitionEntity.toRecord(): TaskDefinitionRecord = TaskDefinitionRecord(
    taskId = taskId,
    name = name,
    enabled = enabled,
    triggerType = triggerType,
    definitionStatus = definitionStatus,
    rawJson = rawJson,
    updatedAt = updatedAt,
)

internal fun TaskDefinitionRecord.toEntity(): TaskDefinitionEntity = TaskDefinitionEntity(
    taskId = taskId,
    name = name,
    enabled = enabled,
    triggerType = triggerType,
    definitionStatus = definitionStatus,
    rawJson = rawJson,
    updatedAt = updatedAt,
)

internal fun TaskScheduleStateEntity.toRecord(): TaskScheduleStateRecord = TaskScheduleStateRecord(
    taskId = taskId,
    nextTriggerAt = nextTriggerAt,
    standbyEnabled = standbyEnabled,
    lastTriggerAt = lastTriggerAt,
    lastScheduleStatus = lastScheduleStatus,
)

internal fun TaskScheduleStateRecord.toEntity(): TaskScheduleStateEntity = TaskScheduleStateEntity(
    taskId = taskId,
    nextTriggerAt = nextTriggerAt,
    standbyEnabled = standbyEnabled,
    lastTriggerAt = lastTriggerAt,
    lastScheduleStatus = lastScheduleStatus,
)

internal fun CredentialProfileEntity.toRecord(): CredentialProfileRecord = CredentialProfileRecord(
    profileId = profileId,
    alias = alias,
    tagsJson = tagsJson,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun CredentialProfileRecord.toEntity(): CredentialProfileEntity = CredentialProfileEntity(
    profileId = profileId,
    alias = alias,
    tagsJson = tagsJson,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun CredentialSecretRecord.toEntity(): CredentialSecretEntity = CredentialSecretEntity(
    profileId = profileId,
    encryptedPayload = encryptedPayload,
    encryptionVersion = encryptionVersion,
    updatedAt = updatedAt,
)

internal fun CredentialSetItemEntity.toRecord(): CredentialSetItemRecord = CredentialSetItemRecord(
    credentialSetId = credentialSetId,
    profileId = profileId,
    orderNo = orderNo,
    enabled = enabled,
)

internal fun CredentialSetItemRecord.toEntity(): CredentialSetItemEntity = CredentialSetItemEntity(
    credentialSetId = credentialSetId,
    profileId = profileId,
    orderNo = orderNo,
    enabled = enabled,
)

internal fun CredentialSetWithItems.toRecord(): CredentialSetRecord = CredentialSetRecord(
    credentialSetId = credentialSet.credentialSetId,
    name = credentialSet.name,
    description = credentialSet.description,
    strategy = credentialSet.strategy,
    enabled = credentialSet.enabled,
    createdAt = credentialSet.createdAt,
    updatedAt = credentialSet.updatedAt,
    items = items.sortedBy { it.orderNo }.map { it.toRecord() },
)

internal fun CredentialSetRecord.toEntity(): CredentialSetEntity = CredentialSetEntity(
    credentialSetId = credentialSetId,
    name = name,
    description = description,
    strategy = strategy,
    enabled = enabled,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun ContinuousSessionEntity.toRecord(): ContinuousSessionRecord = ContinuousSessionRecord(
    sessionId = sessionId,
    taskId = taskId,
    credentialSetId = credentialSetId,
    status = status,
    startedAt = startedAt,
    finishedAt = finishedAt,
    totalCycles = totalCycles,
    successCycles = successCycles,
    failedCycles = failedCycles,
    currentCredentialProfileId = currentCredentialProfileId,
    currentCredentialAlias = currentCredentialAlias,
    nextCredentialProfileId = nextCredentialProfileId,
    nextCredentialAlias = nextCredentialAlias,
    cursorIndex = cursorIndex,
    lastErrorCode = lastErrorCode,
)

internal fun ContinuousSessionRecord.toEntity(): ContinuousSessionEntity = ContinuousSessionEntity(
    sessionId = sessionId,
    taskId = taskId,
    credentialSetId = credentialSetId,
    status = status,
    startedAt = startedAt,
    finishedAt = finishedAt,
    totalCycles = totalCycles,
    successCycles = successCycles,
    failedCycles = failedCycles,
    currentCredentialProfileId = currentCredentialProfileId,
    currentCredentialAlias = currentCredentialAlias,
    nextCredentialProfileId = nextCredentialProfileId,
    nextCredentialAlias = nextCredentialAlias,
    cursorIndex = cursorIndex,
    lastErrorCode = lastErrorCode,
)

internal fun TaskRunEntity.toRecord(): TaskRunRecord = TaskRunRecord(
    runId = runId,
    sessionId = sessionId,
    cycleNo = cycleNo,
    taskId = taskId,
    credentialSetId = credentialSetId,
    credentialProfileId = credentialProfileId,
    credentialAlias = credentialAlias,
    status = status,
    startedAt = startedAt,
    finishedAt = finishedAt,
    durationMs = durationMs,
    triggerType = triggerType,
    errorCode = errorCode,
    message = message,
)

internal fun TaskRunRecord.toEntity(): TaskRunEntity = TaskRunEntity(
    runId = runId,
    sessionId = sessionId,
    cycleNo = cycleNo,
    taskId = taskId,
    credentialSetId = credentialSetId,
    credentialProfileId = credentialProfileId,
    credentialAlias = credentialAlias,
    status = status,
    startedAt = startedAt,
    finishedAt = finishedAt,
    durationMs = durationMs,
    triggerType = triggerType,
    errorCode = errorCode,
    message = message,
)

internal fun StepRunEntity.toRecord(): StepRunRecord = StepRunRecord(
    id = id,
    runId = runId,
    stepId = stepId,
    status = status,
    startedAt = startedAt,
    finishedAt = finishedAt,
    durationMs = durationMs,
    errorCode = errorCode,
    message = message,
    artifactsJson = artifactsJson,
)

internal fun StepRunRecord.toEntity(): StepRunEntity = StepRunEntity(
    id = id,
    runId = runId,
    stepId = stepId,
    status = status,
    startedAt = startedAt,
    finishedAt = finishedAt,
    durationMs = durationMs,
    errorCode = errorCode,
    message = message,
    artifactsJson = artifactsJson,
)
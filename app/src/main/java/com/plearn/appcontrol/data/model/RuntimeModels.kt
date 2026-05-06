package com.plearn.appcontrol.data.model

data class ContinuousSessionRecord(
    val sessionId: String,
    val taskId: String,
    val credentialSetId: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val totalCycles: Int,
    val successCycles: Int,
    val failedCycles: Int,
    val currentCredentialProfileId: String?,
    val currentCredentialAlias: String?,
    val nextCredentialProfileId: String?,
    val nextCredentialAlias: String?,
    val cursorIndex: Int,
    val lastErrorCode: String?,
)

data class TaskRunRecord(
    val runId: String,
    val sessionId: String?,
    val cycleNo: Int?,
    val taskId: String,
    val credentialSetId: String?,
    val credentialProfileId: String?,
    val credentialAlias: String?,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val durationMs: Long?,
    val triggerType: String,
    val errorCode: String?,
    val message: String?,
)

data class StepRunRecord(
    val id: Long = 0,
    val runId: String,
    val stepId: String,
    val status: String,
    val startedAt: Long,
    val finishedAt: Long?,
    val durationMs: Long?,
    val errorCode: String?,
    val message: String?,
    val artifactsJson: String,
)
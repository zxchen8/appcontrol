package com.plearn.appcontrol.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "continuous_sessions",
    foreignKeys = [
        ForeignKey(
            entity = TaskDefinitionEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CredentialSetEntity::class,
            parentColumns = ["credentialSetId"],
            childColumns = ["credentialSetId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["taskId", "status"]), Index(value = ["credentialSetId"])],
)
data class ContinuousSessionEntity(
    @PrimaryKey val sessionId: String,
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

@Entity(
    tableName = "task_runs",
    foreignKeys = [
        ForeignKey(
            entity = TaskDefinitionEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ContinuousSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [Index(value = ["taskId", "startedAt"]), Index(value = ["sessionId"])],
)
data class TaskRunEntity(
    @PrimaryKey val runId: String,
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

@Entity(
    tableName = "step_runs",
    foreignKeys = [
        ForeignKey(
            entity = TaskRunEntity::class,
            parentColumns = ["runId"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["runId", "stepId"])],
)
data class StepRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
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
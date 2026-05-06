package com.plearn.appcontrol.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "task_definitions",
    indices = [Index(value = ["triggerType"]), Index(value = ["definitionStatus"])],
)
data class TaskDefinitionEntity(
    @PrimaryKey val taskId: String,
    val name: String,
    val enabled: Boolean,
    val triggerType: String,
    val definitionStatus: String,
    val rawJson: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "task_schedule_states",
    foreignKeys = [
        ForeignKey(
            entity = TaskDefinitionEntity::class,
            parentColumns = ["taskId"],
            childColumns = ["taskId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["nextTriggerAt"]), Index(value = ["standbyEnabled"])],
)
data class TaskScheduleStateEntity(
    @PrimaryKey val taskId: String,
    val nextTriggerAt: Long?,
    val standbyEnabled: Boolean,
    val lastTriggerAt: Long?,
    val lastScheduleStatus: String?,
)
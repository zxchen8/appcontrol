package com.plearn.appcontrol.data.model

data class TaskDefinitionRecord(
    val taskId: String,
    val name: String,
    val enabled: Boolean,
    val triggerType: String,
    val definitionStatus: String,
    val rawJson: String,
    val updatedAt: Long,
)

data class TaskScheduleStateRecord(
    val taskId: String,
    val nextTriggerAt: Long?,
    val standbyEnabled: Boolean,
    val lastTriggerAt: Long?,
    val lastScheduleStatus: String?,
)
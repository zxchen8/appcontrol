package com.plearn.appcontrol.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "diagnostic_events",
    indices = [Index(value = ["taskId", "createdAt"]), Index(value = ["runId"])],
)
data class DiagnosticsEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: String?,
    val runId: String?,
    val createdAt: Long,
    val eventType: String,
    val payloadJson: String,
)
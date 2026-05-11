package com.plearn.appcontrol.diagnostics

import android.util.Log
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class DiagnosticsCleanupLogEvent(
    val trigger: String,
    val deletedRunCount: Int,
    val beforeBytes: Long,
    val afterBytes: Long,
    val maxStorageBytes: Long,
    val captureHighWatermarkBytes: Long,
)

data class DiagnosticsCaptureGateLogEvent(
    val trigger: String,
    val usedBytes: Long,
    val captureHighWatermarkBytes: Long,
    val maxStorageBytes: Long,
)

interface DiagnosticsRetentionLogger {
    fun logCleanup(event: DiagnosticsCleanupLogEvent)

    fun logCaptureGateDenied(event: DiagnosticsCaptureGateLogEvent)
}

object AndroidDiagnosticsRetentionLogger : DiagnosticsRetentionLogger {
    override fun logCleanup(event: DiagnosticsCleanupLogEvent) {
        Log.i(
            TAG,
            buildJsonObject {
                put("event", "diagnostics_cleanup")
                put("trigger", event.trigger)
                put("deletedRunCount", event.deletedRunCount)
                put("beforeBytes", event.beforeBytes)
                put("afterBytes", event.afterBytes)
                put("maxStorageBytes", event.maxStorageBytes)
                put("captureHighWatermarkBytes", event.captureHighWatermarkBytes)
            }.toString(),
        )
    }

    override fun logCaptureGateDenied(event: DiagnosticsCaptureGateLogEvent) {
        Log.w(
            TAG,
            buildJsonObject {
                put("event", "diagnostics_capture_gate_denied")
                put("trigger", event.trigger)
                put("usedBytes", event.usedBytes)
                put("captureHighWatermarkBytes", event.captureHighWatermarkBytes)
                put("maxStorageBytes", event.maxStorageBytes)
            }.toString(),
        )
    }

    private const val TAG = "DiagRetention"
}
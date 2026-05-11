package com.plearn.appcontrol.diagnostics

import com.plearn.appcontrol.data.model.DiagnosticsEventRecord
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

internal fun DiagnosticsEventRecord.toDiagnosticEventDisplayText(): String? {
    return try {
        val payload = Json.parseToJsonElement(payloadJson).jsonObject
        when (eventType) {
            CAPTURE_GATE_DENIED -> {
                val trigger = payload["trigger"]?.jsonPrimitive?.content ?: return null
                val usedBytes = payload["usedBytes"]?.jsonPrimitive?.longOrNull ?: return null
                val captureHighWatermarkBytes = payload["captureHighWatermarkBytes"]?.jsonPrimitive?.longOrNull ?: return null
                val maxStorageBytes = payload["maxStorageBytes"]?.jsonPrimitive?.longOrNull ?: return null
                "diag=截图采集已拒绝 | 触发=$trigger | 已用=${usedBytes}B | 水位=${captureHighWatermarkBytes}B | 预算=${maxStorageBytes}B"
            }

            CLEANUP -> {
                val trigger = payload["trigger"]?.jsonPrimitive?.content ?: return null
                val deletedRunCount = payload["deletedRunCount"]?.jsonPrimitive?.longOrNull ?: return null
                val beforeBytes = payload["beforeBytes"]?.jsonPrimitive?.longOrNull ?: return null
                val afterBytes = payload["afterBytes"]?.jsonPrimitive?.longOrNull ?: return null
                "diag=诊断产物已清理 | 触发=$trigger | 删除运行=$deletedRunCount | 存储=${beforeBytes}B->${afterBytes}B"
            }

            else -> "diag=$eventType | raw=$payloadJson"
        }
    } catch (_: Throwable) {
        "diag=$eventType | raw=$payloadJson"
    }
}

internal const val CLEANUP = "cleanup"
internal const val CAPTURE_GATE_DENIED = "capture_gate_denied"
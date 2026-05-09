package com.plearn.appcontrol.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal fun String.toDiagnosticArtifactDisplayText(): String? {
    val normalized = trim()
    if (normalized.isBlank() || normalized == "{}" || normalized == "[]") {
        return null
    }

    val structuredArtifact = normalized.toStructuredArtifact() ?: return normalized
    val artifactLabel = structuredArtifact.artifactType.toArtifactLabel() ?: return normalized
    val reasonLabel = structuredArtifact.reason.toReasonLabel()

    return buildString {
        append(artifactLabel)
        if (reasonLabel != null) {
            append(" | 原因=")
            append(reasonLabel)
        }
        structuredArtifact.captureRequested?.let { captureRequested ->
            append(" | 请求截图=")
            append(captureRequested)
        }
        structuredArtifact.sensitiveContextActive?.let { sensitiveContextActive ->
            append(" | 敏感上下文=")
            append(sensitiveContextActive)
        }
        if (structuredArtifact.hasAdditionalFields) {
            append(" | raw=")
            append(normalized)
        }
    }
}

private fun String.toStructuredArtifact(): StructuredArtifact? = try {
    val jsonObject = Json.parseToJsonElement(this).jsonObject
    val artifactType = jsonObject["artifactType"]?.jsonPrimitive?.contentOrNull
    val reason = jsonObject["reason"]?.jsonPrimitive?.contentOrNull
    val captureRequested = jsonObject["captureRequested"]?.jsonPrimitive?.booleanOrNull
    val sensitiveContextActive = jsonObject["sensitiveContextActive"]?.jsonPrimitive?.booleanOrNull

    if (artifactType == null && reason == null && captureRequested == null && sensitiveContextActive == null) {
        null
    } else {
        StructuredArtifact(
            artifactType = artifactType,
            reason = reason,
            captureRequested = captureRequested,
            sensitiveContextActive = sensitiveContextActive,
            hasAdditionalFields = jsonObject.keys.any { it !in structuredArtifactKeys },
        )
    }
} catch (_: IllegalArgumentException) {
    null
}

private fun String?.toArtifactLabel(): String? = when (this) {
    "screenshot_suppressed" -> "截图已抑制"
    "screenshot_skipped" -> "截图已跳过"
    "screenshot_unavailable" -> "截图不可用"
    null -> null
    else -> null
}

private fun String?.toReasonLabel(): String? = when (this) {
    "DIAG_SCREENSHOT_SUPPRESSED_FOR_SENSITIVE_CONTENT" -> "敏感内容，禁止采集 (DIAG_SCREENSHOT_SUPPRESSED_FOR_SENSITIVE_CONTENT)"
    "DIAG_SCREENSHOT_CAPTURE_DISABLED_BY_POLICY" -> "诊断策略已关闭截图采集 (DIAG_SCREENSHOT_CAPTURE_DISABLED_BY_POLICY)"
    "DIAG_SCREENSHOT_CAPTURE_NOT_IMPLEMENTED" -> "当前版本未实现截图采集 (DIAG_SCREENSHOT_CAPTURE_NOT_IMPLEMENTED)"
    "DIAG_SCREENSHOT_NOT_CAPTURED_BEFORE_FIRST_ACTION_BLOCK" -> "阻断发生在首个动作步骤前，未采集截图 (DIAG_SCREENSHOT_NOT_CAPTURED_BEFORE_FIRST_ACTION_BLOCK)"
    null -> null
    else -> this
}

private data class StructuredArtifact(
    val artifactType: String?,
    val reason: String?,
    val captureRequested: Boolean?,
    val sensitiveContextActive: Boolean?,
    val hasAdditionalFields: Boolean,
)

private val structuredArtifactKeys = setOf(
    "artifactType",
    "reason",
    "captureRequested",
    "sensitiveContextActive",
)
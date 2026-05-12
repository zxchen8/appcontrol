package com.plearn.appcontrol.platform.devicecontrol

import com.plearn.appcontrol.capability.CapabilityFailureCode
import com.plearn.appcontrol.capability.CapabilityResult
import com.plearn.appcontrol.capability.DeviceControlPort
import com.plearn.appcontrol.capability.ElementSelector
import com.plearn.appcontrol.capability.InputTextRequest
import com.plearn.appcontrol.capability.InputTextSummary
import com.plearn.appcontrol.capability.ScreenPoint
import com.plearn.appcontrol.capability.SelectorType
import com.plearn.appcontrol.capability.ScreenshotCapture
import com.plearn.appcontrol.capability.ScreenshotCaptureRequest
import com.plearn.appcontrol.capability.SwipeRequest
import java.io.File
import java.io.IOException

class DeterministicDeviceControlPort(
    private val screenshotRootDir: File = File("build/tmp/deterministic-device-control/screenshots"),
    private val screenshotRelativeRootPath: String = "diagnostics/screenshots",
    private val screenshotFileNameFactory: (ScreenshotCaptureRequest) -> String = ::buildScreenshotFileName,
) : DeviceControlPort {
    override suspend fun startApp(packageName: String): CapabilityResult<Unit> {
        validatePackageName(packageName)?.let { return it }
        return CapabilityResult.Success(Unit)
    }

    override suspend fun stopApp(packageName: String): CapabilityResult<Unit> {
        validatePackageName(packageName)?.let { return it }
        return CapabilityResult.Success(Unit)
    }

    override suspend fun tap(point: ScreenPoint): CapabilityResult<Unit> = CapabilityResult.Success(Unit)

    override suspend fun swipe(request: SwipeRequest): CapabilityResult<Unit> = CapabilityResult.Success(Unit)

    override suspend fun inputText(request: InputTextRequest): CapabilityResult<InputTextSummary> =
        CapabilityResult.Success(
            InputTextSummary(
                selectorSummary = request.selector?.toSummary(),
                source = request.source,
                masked = request.masked,
            ),
        )

    override suspend fun captureScreenshot(request: ScreenshotCaptureRequest): CapabilityResult<ScreenshotCapture> {
        val taskDirectory = screenshotRootDir.resolve(sanitizePathSegment(request.taskId))
        val runDirectory = taskDirectory.resolve(sanitizePathSegment(request.runId))
        if (!runDirectory.exists() && !runDirectory.mkdirs()) {
            return CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_EXECUTION_FAILED,
                message = "Failed to create diagnostics screenshot directory.",
            )
        }

        return try {
            val fileName = screenshotFileNameFactory(request)
            val outputFile = runDirectory.resolve(fileName)
            outputFile.writeBytes(PLACEHOLDER_PNG_BYTES)
            val relativePath = buildString {
                append(screenshotRelativeRootPath.trimEnd('/'))
                append('/')
                append(sanitizePathSegment(request.taskId))
                append('/')
                append(sanitizePathSegment(request.runId))
                append('/')
                append(fileName)
            }
            CapabilityResult.Success(
                ScreenshotCapture(
                    relativePath = relativePath,
                    mimeType = PNG_MIME_TYPE,
                    fileSizeBytes = outputFile.length(),
                ),
            )
        } catch (error: IOException) {
            CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_EXECUTION_FAILED,
                message = error.message ?: "Failed to create deterministic screenshot.",
            )
        }
    }

    private fun validatePackageName(packageName: String): CapabilityResult.Failure? {
        if (!PACKAGE_NAME_REGEX.matches(packageName)) {
            return CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_INVALID_ARGUMENT,
                message = "Invalid package name: $packageName",
            )
        }

        return null
    }

    private fun sanitizePathSegment(value: String): String =
        value.replace(PATH_SEGMENT_REGEX, "_").ifBlank { "artifact" }

    private fun ElementSelector.toSummary(): String =
        when (by) {
            SelectorType.RESOURCE_ID -> "resourceId=$value"
            SelectorType.TEXT -> "text=$value"
            SelectorType.CONTENT_DESCRIPTION -> "contentDescription=$value"
            SelectorType.CLASS_NAME -> "className=$value"
        }

    private companion object {
        val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
        val PATH_SEGMENT_REGEX = Regex("[^a-zA-Z0-9._-]")
        const val RUN_SCOPE_SEGMENT = "run"
        const val PNG_MIME_TYPE = "image/png"
        val PLACEHOLDER_PNG_BYTES = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(),
            0x89.toByte(),
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x44, 0x41, 0x54,
            0x78, 0x9C.toByte(), 0x63, 0x60, 0x00, 0x00, 0x00, 0x02,
            0x00, 0x01, 0xE5.toByte(), 0x27, 0xD4.toByte(), 0xA2.toByte(),
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
        )

        private fun buildScreenshotFileName(request: ScreenshotCaptureRequest): String {
            val taskAttemptSuffix = request.taskAttempt
                ?.takeIf { it > 1 }
                ?.let { "-task$it" }
                .orEmpty()
            val scopeSegment = request.stepId
                ?.replace(PATH_SEGMENT_REGEX, "_")
                ?.ifBlank { "artifact" }
                ?: RUN_SCOPE_SEGMENT
            return if (request.stepId == null) {
                "$scopeSegment$taskAttemptSuffix.png"
            } else {
                val stepIdHash = request.stepId.hashCode().toUInt().toString(16)
                val attemptSuffix = request.attempt?.let { "-attempt$it" } ?: ""
                "$scopeSegment-$stepIdHash$taskAttemptSuffix$attemptSuffix.png"
            }
        }
    }
}
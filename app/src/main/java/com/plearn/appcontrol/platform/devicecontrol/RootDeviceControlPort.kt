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

class RootDeviceControlPort(
    private val shell: RootShellPort,
    private val screenshotRootDir: File = File("build/tmp/root-device-control/screenshots"),
    private val screenshotRelativeRootPath: String = "diagnostics/screenshots",
    private val screenshotFileNameFactory: (ScreenshotCaptureRequest) -> String = ::buildScreenshotFileName,
) : DeviceControlPort {
    override suspend fun startApp(packageName: String): CapabilityResult<Unit> {
        validatePackageName(packageName)?.let { return it }
        return runUnitCommand("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
    }

    override suspend fun stopApp(packageName: String): CapabilityResult<Unit> {
        validatePackageName(packageName)?.let { return it }
        return runUnitCommand("am force-stop $packageName")
    }

    override suspend fun tap(point: ScreenPoint): CapabilityResult<Unit> =
        runUnitCommand("input tap ${point.x} ${point.y}")

    override suspend fun swipe(request: SwipeRequest): CapabilityResult<Unit> =
        runUnitCommand(
            "input swipe ${request.from.x} ${request.from.y} ${request.to.x} ${request.to.y} ${request.durationMs}",
        )

    override suspend fun inputText(request: InputTextRequest): CapabilityResult<InputTextSummary> {
        if (request.clearBeforeInput) {
            val clearResult = runUnitCommand("input keyevent KEYCODE_MOVE_END && input keyevent --longpress KEYCODE_DEL")
            if (clearResult is CapabilityResult.Failure) {
                return clearResult
            }
        }

        val commandResult = shell.run("input text ${quoteInputTextArgument(request.text)}")
        if (commandResult.exitCode != 0) {
            return CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_EXECUTION_FAILED,
                message = commandResult.errorMessage(),
            )
        }

        return CapabilityResult.Success(
            InputTextSummary(
                selectorSummary = request.selector?.toSummary(),
                source = request.source,
                masked = request.masked,
            ),
        )
    }

    override suspend fun captureScreenshot(request: ScreenshotCaptureRequest): CapabilityResult<ScreenshotCapture> {
        val taskDirectory = screenshotRootDir.resolve(sanitizePathSegment(request.taskId))
        val runDirectory = taskDirectory.resolve(sanitizePathSegment(request.runId))
        if (!runDirectory.exists() && !runDirectory.mkdirs()) {
            return CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_EXECUTION_FAILED,
                message = "Failed to create diagnostics screenshot directory.",
            )
        }

        val fileName = screenshotFileNameFactory(request)
        val outputFile = runDirectory.resolve(fileName)
        val commandResult = shell.run("screencap -p ${quoteShellArgument(outputFile.absolutePath)}")
        if (commandResult.exitCode != 0) {
            return CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_EXECUTION_FAILED,
                message = commandResult.errorMessage(),
            )
        }
        if (!outputFile.isFile || outputFile.length() <= 0L) {
            return CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_EXECUTION_FAILED,
                message = "Screenshot file was not created.",
            )
        }

        val relativePath = buildString {
            append(screenshotRelativeRootPath.trimEnd('/'))
            append('/')
            append(sanitizePathSegment(request.taskId))
            append('/')
            append(sanitizePathSegment(request.runId))
            append('/')
            append(fileName)
        }
        return CapabilityResult.Success(
            ScreenshotCapture(
                relativePath = relativePath,
                mimeType = PNG_MIME_TYPE,
                fileSizeBytes = outputFile.length(),
            ),
        )
    }

    private suspend fun runUnitCommand(command: String): CapabilityResult<Unit> {
        val commandResult = shell.run(command)
        if (commandResult.exitCode != 0) {
            return CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_EXECUTION_FAILED,
                message = commandResult.errorMessage(),
            )
        }

        return CapabilityResult.Success(Unit)
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

    private fun quoteInputTextArgument(rawText: String): String = buildString(rawText.length + 2) {
        append('\'')
        rawText.forEach { character ->
            when (character) {
                ' ', '\n', '\r', '\t' -> append("%s")
                '\'' -> append("'\"'\"'")
                else -> append(character)
            }
        }
        append('\'')
    }

    private fun quoteShellArgument(argument: String): String = buildString(argument.length + 2) {
        append('\'')
        argument.forEach { character ->
            if (character == '\'') {
                append("'\"'\"'")
            } else {
                append(character)
            }
        }
        append('\'')
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

interface RootShellPort {
    suspend fun run(command: String): ShellCommandResult
}

data class ShellCommandResult(
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
) {
    fun errorMessage(): String = stderr.ifBlank { stdout.ifBlank { "Shell command failed." } }
}
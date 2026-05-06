package com.plearn.appcontrol.platform.devicecontrol

import com.plearn.appcontrol.capability.CapabilityFailureCode
import com.plearn.appcontrol.capability.CapabilityResult
import com.plearn.appcontrol.capability.DeviceControlPort
import com.plearn.appcontrol.capability.ElementSelector
import com.plearn.appcontrol.capability.InputTextRequest
import com.plearn.appcontrol.capability.InputTextSummary
import com.plearn.appcontrol.capability.ScreenPoint
import com.plearn.appcontrol.capability.SelectorType
import com.plearn.appcontrol.capability.SwipeRequest

class RootDeviceControlPort(
    private val shell: RootShellPort,
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

        val commandResult = shell.run("input text ${escapeInputText(request.text)}")
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

    private fun escapeInputText(rawText: String): String = buildString(rawText.length) {
        rawText.forEach { character ->
            when (character) {
                ' ' -> append("%s")
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\'' -> append("\\'")
                '$', '&', '|', ';', '<', '>', '(', ')' -> append('\\').append(character)
                else -> append(character)
            }
        }
    }

    private fun ElementSelector.toSummary(): String =
        when (by) {
            SelectorType.RESOURCE_ID -> "resourceId=$value"
            SelectorType.TEXT -> "text=$value"
            SelectorType.CONTENT_DESCRIPTION -> "contentDescription=$value"
            SelectorType.CLASS_NAME -> "className=$value"
        }

    private companion object {
        val PACKAGE_NAME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
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
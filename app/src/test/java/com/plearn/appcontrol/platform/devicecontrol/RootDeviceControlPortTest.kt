package com.plearn.appcontrol.platform.devicecontrol

import com.plearn.appcontrol.capability.CapabilityFailureCode
import com.plearn.appcontrol.capability.CapabilityResult
import com.plearn.appcontrol.capability.ElementSelector
import com.plearn.appcontrol.capability.InputTextRequest
import com.plearn.appcontrol.capability.InputTextSummary
import com.plearn.appcontrol.capability.InputTextSource
import com.plearn.appcontrol.capability.ScreenPoint
import com.plearn.appcontrol.capability.SelectorType
import com.plearn.appcontrol.capability.SwipeRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RootDeviceControlPortTest {
    @Test
    fun shouldBuildStartAndStopAppShellCommands() = runBlocking {
        val shell = RecordingRootShellPort()
        val port = RootDeviceControlPort(shell)

        val startResult = port.startApp("com.example.target")
        val stopResult = port.stopApp("com.example.target")

        assertTrue(startResult is CapabilityResult.Success<*>)
        assertTrue(stopResult is CapabilityResult.Success<*>)
        assertEquals(
            listOf(
                "monkey -p com.example.target -c android.intent.category.LAUNCHER 1",
                "am force-stop com.example.target",
            ),
            shell.commands,
        )
    }

    @Test
    fun shouldBuildTapAndSwipeShellCommands() = runBlocking {
        val shell = RecordingRootShellPort()
        val port = RootDeviceControlPort(shell)

        val tapResult = port.tap(ScreenPoint(x = 12, y = 34))
        val swipeResult = port.swipe(
            SwipeRequest(
                from = ScreenPoint(x = 1, y = 2),
                to = ScreenPoint(x = 3, y = 4),
                durationMs = 500L,
            ),
        )

        assertTrue(tapResult is CapabilityResult.Success<*>)
        assertTrue(swipeResult is CapabilityResult.Success<*>)
        assertEquals(
            listOf(
                "input tap 12 34",
                "input swipe 1 2 3 4 500",
            ),
            shell.commands,
        )
    }

    @Test
    fun shouldReturnMaskedInputSummaryWithoutLeakingRawText() = runBlocking {
        val shell = RecordingRootShellPort()
        val port = RootDeviceControlPort(shell)

        val result = port.inputText(
            InputTextRequest(
                text = "secret password",
                selector = ElementSelector(
                    by = SelectorType.RESOURCE_ID,
                    value = "com.example.target:id/password",
                ),
                source = InputTextSource.VARIABLE_REFERENCE,
                masked = true,
            ),
        )

        assertTrue(result is CapabilityResult.Success<*>)
        assertEquals(listOf("input text 'secret%spassword'"), shell.commands)
        val summary = (result as CapabilityResult.Success<InputTextSummary>).value
        assertEquals("resourceId=com.example.target:id/password", summary.selectorSummary)
        assertEquals(InputTextSource.VARIABLE_REFERENCE, summary.source)
        assertTrue(summary.masked)
        assertFalse(summary.toString().contains("secret password"))
    }

    @Test
    fun shouldClearFocusedInputBeforeTypingWhenRequested() = runBlocking {
        val shell = RecordingRootShellPort()
        val port = RootDeviceControlPort(shell)

        val result = port.inputText(
            InputTextRequest(
                text = "secret password",
                clearBeforeInput = true,
            ),
        )

        assertTrue(result is CapabilityResult.Success<*>)
        assertEquals(
            listOf(
                "input keyevent KEYCODE_MOVE_END && input keyevent --longpress KEYCODE_DEL",
                "input text 'secret%spassword'",
            ),
            shell.commands,
        )
    }

    @Test
    fun shouldEscapeShellSensitiveCharactersInInputTextCommand() = runBlocking {
        val shell = RecordingRootShellPort()
        val port = RootDeviceControlPort(shell)

        val result = port.inputText(InputTextRequest(text = "pa\$\$ word&1"))

        assertTrue(result is CapabilityResult.Success<*>)
        assertEquals(listOf("input text 'pa\$\$%sword&1'"), shell.commands)
    }

    @Test
    fun shouldQuoteShellArgumentWhenInputContainsControlOrShellCharacters() = runBlocking {
        val shell = RecordingRootShellPort()
        val port = RootDeviceControlPort(shell)

        val result = port.inputText(InputTextRequest(text = "ok`\nreboot'soon"))

        assertTrue(result is CapabilityResult.Success<*>)
        assertEquals(listOf("input text 'ok`%sreboot'\"'\"'soon'"), shell.commands)
    }

    @Test
    fun shouldReturnExecutionFailureWhenShellCommandFails() = runBlocking {
        val shell = RecordingRootShellPort(
            nextResult = ShellCommandResult(
                exitCode = 1,
                stderr = "permission denied",
            ),
        )
        val port = RootDeviceControlPort(shell)

        val result = port.stopApp("com.example.target")

        assertTrue(result is CapabilityResult.Failure)
        assertEquals(CapabilityFailureCode.STEP_EXECUTION_FAILED, (result as CapabilityResult.Failure).errorCode)
        assertTrue(result.message.contains("permission denied"))
    }

    @Test
    fun shouldRejectUnsafePackageNameBeforeRunningShellCommand() = runBlocking {
        val shell = RecordingRootShellPort()
        val port = RootDeviceControlPort(shell)

        val result = port.startApp("com.example.target;rm")

        assertTrue(result is CapabilityResult.Failure)
        assertEquals(CapabilityFailureCode.STEP_INVALID_ARGUMENT, (result as CapabilityResult.Failure).errorCode)
        assertEquals(emptyList<String>(), shell.commands)
    }

    private class RecordingRootShellPort(
        private val nextResult: ShellCommandResult = ShellCommandResult(exitCode = 0),
    ) : RootShellPort {
        val commands = mutableListOf<String>()

        override suspend fun run(command: String): ShellCommandResult {
            commands += command
            return nextResult
        }
    }
}
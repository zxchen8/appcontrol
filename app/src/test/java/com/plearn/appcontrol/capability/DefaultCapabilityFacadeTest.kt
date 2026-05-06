package com.plearn.appcontrol.capability

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCapabilityFacadeTest {
    @Test
    fun shouldComposeRestartAppWithStopThenStartInCapabilityFacade() = runBlocking {
        val deviceControl = RecordingDeviceControlPort()
        val facade = DefaultCapabilityFacade(
            deviceControl = deviceControl,
            accessibility = RecordingAccessibilityPort(),
            vision = DisabledVisionPort,
        )

        val result = facade.restartApp(
            packageName = "com.example.target",
            waitAfterStopMs = 0L,
        )

        assertTrue(result is CapabilityResult.Success)
        assertEquals(
            listOf(
                "stop:com.example.target",
                "start:com.example.target",
            ),
            deviceControl.operations,
        )
    }

    @Test
    fun shouldTapElementTargetViaAccessibilityPort() = runBlocking {
        val deviceControl = RecordingDeviceControlPort()
        val accessibility = RecordingAccessibilityPort()
        val facade = DefaultCapabilityFacade(
            deviceControl = deviceControl,
            accessibility = accessibility,
            vision = DisabledVisionPort,
        )
        val selector = ElementSelector(
            by = SelectorType.TEXT,
            value = "登录",
        )

        val result = facade.tap(TapTarget.Element(selector))

        assertTrue(result is CapabilityResult.Success)
        assertEquals(emptyList<String>(), deviceControl.operations)
        assertEquals(listOf(selector), accessibility.tappedSelectors)
    }

    @Test
    fun shouldTapCoordinateTargetViaDeviceControlPort() = runBlocking {
        val deviceControl = RecordingDeviceControlPort()
        val facade = DefaultCapabilityFacade(
            deviceControl = deviceControl,
            accessibility = RecordingAccessibilityPort(),
            vision = DisabledVisionPort,
        )

        val result = facade.tap(TapTarget.Coordinate(ScreenPoint(x = 120, y = 360)))

        assertTrue(result is CapabilityResult.Success)
        assertEquals(listOf("tap:120,360"), deviceControl.operations)
    }

    @Test
    fun shouldSwipeAndInputTextViaDeviceControlPort() = runBlocking {
        val deviceControl = RecordingDeviceControlPort().apply {
            nextInputSummary = InputTextSummary(
                selectorSummary = "resourceId=com.example.target:id/password",
                source = InputTextSource.VARIABLE_REFERENCE,
                masked = true,
            )
        }
        val facade = DefaultCapabilityFacade(
            deviceControl = deviceControl,
            accessibility = RecordingAccessibilityPort(),
            vision = DisabledVisionPort,
        )

        val swipeResult = facade.swipe(
            SwipeRequest(
                from = ScreenPoint(x = 1, y = 2),
                to = ScreenPoint(x = 3, y = 4),
                durationMs = 500L,
            ),
        )
        val inputRequest = InputTextRequest(
            text = "secret-value",
            selector = ElementSelector(
                by = SelectorType.RESOURCE_ID,
                value = "com.example.target:id/password",
            ),
            source = InputTextSource.VARIABLE_REFERENCE,
            masked = true,
        )
        val inputResult = facade.inputText(inputRequest)

        assertTrue(swipeResult is CapabilityResult.Success)
        assertEquals(listOf("swipe:1,2->3,4@500"), deviceControl.operations.take(1))
        assertTrue(inputResult is CapabilityResult.Success)
        assertSame(inputRequest, deviceControl.lastInputRequest)
        assertEquals(true, (inputResult as CapabilityResult.Success).value.masked)
        assertEquals(InputTextSource.VARIABLE_REFERENCE, inputResult.value.source)
    }

    @Test
    fun shouldWaitForElementViaAccessibilityPort() = runBlocking {
        val accessibility = RecordingAccessibilityPort()
        val facade = DefaultCapabilityFacade(
            deviceControl = RecordingDeviceControlPort(),
            accessibility = accessibility,
            vision = DisabledVisionPort,
        )
        val selector = ElementSelector(
            by = SelectorType.CONTENT_DESCRIPTION,
            value = "首页",
        )

        val result = facade.waitForElement(
            selector = selector,
            state = WaitElementState.APPEARED,
            timeoutMs = 3_000L,
        )

        assertTrue(result is CapabilityResult.Success)
        assertEquals(listOf(WaitCall(selector, WaitElementState.APPEARED, 3_000L)), accessibility.waitCalls)
    }

    @Test
    fun shouldReturnCapabilityUnavailableWhenTapRequiresVision() = runBlocking {
        val facade = DefaultCapabilityFacade(
            deviceControl = RecordingDeviceControlPort(),
            accessibility = RecordingAccessibilityPort(),
            vision = DisabledVisionPort,
        )

        val result = facade.tap(TapTarget.OcrText("立即开始"))

        assertTrue(result is CapabilityResult.Failure)
        assertEquals(
            CapabilityFailureCode.STEP_CAPABILITY_UNAVAILABLE,
            (result as CapabilityResult.Failure).errorCode,
        )
    }

    private class RecordingDeviceControlPort : DeviceControlPort {
        val operations = mutableListOf<String>()
        var nextInputSummary: InputTextSummary = InputTextSummary(
            selectorSummary = null,
            source = InputTextSource.LITERAL,
            masked = false,
        )
        var lastInputRequest: InputTextRequest? = null

        override suspend fun startApp(packageName: String): CapabilityResult<Unit> {
            operations += "start:$packageName"
            return CapabilityResult.Success(Unit)
        }

        override suspend fun stopApp(packageName: String): CapabilityResult<Unit> {
            operations += "stop:$packageName"
            return CapabilityResult.Success(Unit)
        }

        override suspend fun tap(point: ScreenPoint): CapabilityResult<Unit> {
            operations += "tap:${point.x},${point.y}"
            return CapabilityResult.Success(Unit)
        }

        override suspend fun swipe(request: SwipeRequest): CapabilityResult<Unit> {
            operations += "swipe:${request.from.x},${request.from.y}->${request.to.x},${request.to.y}@${request.durationMs}"
            return CapabilityResult.Success(Unit)
        }

        override suspend fun inputText(request: InputTextRequest): CapabilityResult<InputTextSummary> {
            lastInputRequest = request
            operations += "input:${request.source}:${request.masked}"
            return CapabilityResult.Success(nextInputSummary)
        }
    }

    private class RecordingAccessibilityPort : AccessibilityPort {
        val tappedSelectors = mutableListOf<ElementSelector>()
        val waitCalls = mutableListOf<WaitCall>()

        override suspend fun tap(selector: ElementSelector): CapabilityResult<Unit> {
            tappedSelectors += selector
            return CapabilityResult.Success(Unit)
        }

        override suspend fun waitForElement(
            selector: ElementSelector,
            state: WaitElementState,
            timeoutMs: Long,
        ): CapabilityResult<Unit> {
            waitCalls += WaitCall(selector, state, timeoutMs)
            return CapabilityResult.Success(Unit)
        }
    }

    private data class WaitCall(
        val selector: ElementSelector,
        val state: WaitElementState,
        val timeoutMs: Long,
    )
}
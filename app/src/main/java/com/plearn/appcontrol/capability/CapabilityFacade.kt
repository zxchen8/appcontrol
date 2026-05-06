package com.plearn.appcontrol.capability

import kotlinx.coroutines.delay

interface CapabilityFacade {
    suspend fun startApp(packageName: String): CapabilityResult<Unit>
    suspend fun stopApp(packageName: String): CapabilityResult<Unit>
    suspend fun restartApp(packageName: String, waitAfterStopMs: Long = 0L): CapabilityResult<Unit>
    suspend fun tap(target: TapTarget): CapabilityResult<Unit>
    suspend fun swipe(request: SwipeRequest): CapabilityResult<Unit>
    suspend fun inputText(request: InputTextRequest): CapabilityResult<InputTextSummary>
    suspend fun waitForElement(
        selector: ElementSelector,
        state: WaitElementState,
        timeoutMs: Long,
    ): CapabilityResult<Unit>
}

class DefaultCapabilityFacade(
    private val deviceControl: DeviceControlPort,
    private val accessibility: AccessibilityPort,
    private val vision: VisionPort,
) : CapabilityFacade {
    override suspend fun startApp(packageName: String): CapabilityResult<Unit> =
        deviceControl.startApp(packageName)

    override suspend fun stopApp(packageName: String): CapabilityResult<Unit> =
        deviceControl.stopApp(packageName)

    override suspend fun restartApp(packageName: String, waitAfterStopMs: Long): CapabilityResult<Unit> {
        when (val stopResult = deviceControl.stopApp(packageName)) {
            is CapabilityResult.Failure -> return stopResult
            is CapabilityResult.Success -> Unit
        }

        if (waitAfterStopMs > 0L) {
            delay(waitAfterStopMs)
        }

        return deviceControl.startApp(packageName)
    }

    override suspend fun tap(target: TapTarget): CapabilityResult<Unit> =
        when (target) {
            is TapTarget.Coordinate -> deviceControl.tap(target.point)
            is TapTarget.Element -> accessibility.tap(target.selector)
            is TapTarget.Image,
            is TapTarget.OcrText,
            -> CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_CAPABILITY_UNAVAILABLE,
                message = "Vision capability is not enabled.",
            )
        }

    override suspend fun swipe(request: SwipeRequest): CapabilityResult<Unit> =
        deviceControl.swipe(request)

    override suspend fun inputText(request: InputTextRequest): CapabilityResult<InputTextSummary> =
        deviceControl.inputText(request)

    override suspend fun waitForElement(
        selector: ElementSelector,
        state: WaitElementState,
        timeoutMs: Long,
    ): CapabilityResult<Unit> = accessibility.waitForElement(selector, state, timeoutMs)
}

sealed interface TapTarget {
    data class Coordinate(val point: ScreenPoint) : TapTarget

    data class Element(val selector: ElementSelector) : TapTarget

    data class OcrText(val text: String) : TapTarget

    data class Image(val templateId: String) : TapTarget
}

sealed interface CapabilityResult<out T> {
    data class Success<T>(val value: T) : CapabilityResult<T>

    data class Failure(
        val errorCode: String,
        val message: String,
    ) : CapabilityResult<Nothing>
}

object CapabilityFailureCode {
    const val STEP_CAPABILITY_UNAVAILABLE = "STEP_CAPABILITY_UNAVAILABLE"
    const val STEP_EXECUTION_FAILED = "STEP_EXECUTION_FAILED"
    const val STEP_ELEMENT_NOT_FOUND = "STEP_ELEMENT_NOT_FOUND"
    const val STEP_TIMEOUT = "STEP_TIMEOUT"
    const val STEP_INVALID_ARGUMENT = "STEP_INVALID_ARGUMENT"
}

data class ScreenPoint(
    val x: Int,
    val y: Int,
)

data class SwipeRequest(
    val from: ScreenPoint,
    val to: ScreenPoint,
    val durationMs: Long,
)

data class InputTextRequest(
    val text: String,
    val selector: ElementSelector? = null,
    val clearBeforeInput: Boolean = false,
    val source: InputTextSource = InputTextSource.LITERAL,
    val masked: Boolean = false,
)

data class InputTextSummary(
    val selectorSummary: String?,
    val source: InputTextSource,
    val masked: Boolean,
)

enum class InputTextSource {
    LITERAL,
    VARIABLE_REFERENCE,
}

data class ElementSelector(
    val by: SelectorType,
    val value: String,
)

enum class SelectorType {
    RESOURCE_ID,
    TEXT,
    CONTENT_DESCRIPTION,
    CLASS_NAME,
}

enum class WaitElementState {
    APPEARED,
    DISAPPEARED,
}

interface DeviceControlPort {
    suspend fun startApp(packageName: String): CapabilityResult<Unit>
    suspend fun stopApp(packageName: String): CapabilityResult<Unit>
    suspend fun tap(point: ScreenPoint): CapabilityResult<Unit>
    suspend fun swipe(request: SwipeRequest): CapabilityResult<Unit>
    suspend fun inputText(request: InputTextRequest): CapabilityResult<InputTextSummary>
}

interface AccessibilityPort {
    suspend fun tap(selector: ElementSelector): CapabilityResult<Unit>

    suspend fun waitForElement(
        selector: ElementSelector,
        state: WaitElementState,
        timeoutMs: Long,
    ): CapabilityResult<Unit>
}

interface VisionPort

object DisabledVisionPort : VisionPort
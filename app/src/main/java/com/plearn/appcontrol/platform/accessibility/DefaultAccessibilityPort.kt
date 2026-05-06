package com.plearn.appcontrol.platform.accessibility

import com.plearn.appcontrol.capability.AccessibilityPort
import com.plearn.appcontrol.capability.CapabilityFailureCode
import com.plearn.appcontrol.capability.CapabilityResult
import com.plearn.appcontrol.capability.ElementSelector
import com.plearn.appcontrol.capability.WaitElementState
import kotlinx.coroutines.delay

class DefaultAccessibilityPort(
    private val nodeTreeAdapter: NodeTreeAdapter,
    private val pauseController: PauseController = CoroutinePauseController,
    private val pollIntervalMs: Long = 250L,
) : AccessibilityPort {
    override suspend fun tap(selector: ElementSelector): CapabilityResult<Unit> {
        val node = nodeTreeAdapter.findFirst(selector)
            ?: return CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_ELEMENT_NOT_FOUND,
                message = "No accessibility node matched selector.",
            )

        return if (nodeTreeAdapter.click(node)) {
            CapabilityResult.Success(Unit)
        } else {
            CapabilityResult.Failure(
                errorCode = CapabilityFailureCode.STEP_EXECUTION_FAILED,
                message = "Accessibility click failed.",
            )
        }
    }

    override suspend fun waitForElement(
        selector: ElementSelector,
        state: WaitElementState,
        timeoutMs: Long,
    ): CapabilityResult<Unit> {
        val safePollIntervalMs = pollIntervalMs.coerceAtLeast(1L)
        val attemptCount = (timeoutMs.coerceAtLeast(0L) / safePollIntervalMs) + 1

        for (attempt in 0 until attemptCount) {
            val isPresent = nodeTreeAdapter.findFirst(selector) != null
            val matched = when (state) {
                WaitElementState.APPEARED -> isPresent
                WaitElementState.DISAPPEARED -> !isPresent
            }
            if (matched) {
                return CapabilityResult.Success(Unit)
            }

            if (attempt + 1 < attemptCount) {
                pauseController.pause(safePollIntervalMs)
            }
        }

        return CapabilityResult.Failure(
            errorCode = CapabilityFailureCode.STEP_TIMEOUT,
            message = "Timed out waiting for selector state: $state.",
        )
    }
}

data class AccessibilityNodeSnapshot(
    val nodeId: String,
)

interface NodeTreeAdapter {
    suspend fun findFirst(selector: ElementSelector): AccessibilityNodeSnapshot?

    suspend fun click(node: AccessibilityNodeSnapshot): Boolean
}

interface PauseController {
    suspend fun pause(durationMs: Long)
}

object CoroutinePauseController : PauseController {
    override suspend fun pause(durationMs: Long) {
        delay(durationMs)
    }
}
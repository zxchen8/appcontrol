package com.plearn.appcontrol.platform.accessibility

import com.plearn.appcontrol.capability.CapabilityFailureCode
import com.plearn.appcontrol.capability.CapabilityResult
import com.plearn.appcontrol.capability.ElementSelector
import com.plearn.appcontrol.capability.SelectorType
import com.plearn.appcontrol.capability.WaitElementState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultAccessibilityPortTest {
    @Test
    fun shouldTapMatchingNodeViaNodeTreeAdapter() = runBlocking {
        val adapter = ScriptedNodeTreeAdapter(
            responses = listOf(
                AccessibilityNodeSnapshot(nodeId = "login-button"),
            ),
        )
        val port = DefaultAccessibilityPort(
            nodeTreeAdapter = adapter,
            pauseController = NoOpPauseController,
            pollIntervalMs = 1_000L,
        )

        val result = port.tap(ElementSelector(SelectorType.TEXT, "登录"))

        assertTrue(result is CapabilityResult.Success<*>)
        assertEquals(listOf("login-button"), adapter.clickedNodeIds)
    }

    @Test
    fun shouldReturnElementNotFoundWhenTapSelectorMissing() = runBlocking {
        val adapter = ScriptedNodeTreeAdapter(responses = listOf(null))
        val port = DefaultAccessibilityPort(
            nodeTreeAdapter = adapter,
            pauseController = NoOpPauseController,
            pollIntervalMs = 1_000L,
        )

        val result = port.tap(ElementSelector(SelectorType.RESOURCE_ID, "com.example.target:id/login"))

        assertTrue(result is CapabilityResult.Failure)
        assertEquals(
            CapabilityFailureCode.STEP_ELEMENT_NOT_FOUND,
            (result as CapabilityResult.Failure).errorCode,
        )
    }

    @Test
    fun shouldReturnCapabilityUnavailableWhenAccessibilityServiceIsDisconnected() = runBlocking {
        val adapter = ScriptedNodeTreeAdapter(
            responses = emptyList(),
            available = false,
        )
        val port = DefaultAccessibilityPort(
            nodeTreeAdapter = adapter,
            pauseController = NoOpPauseController,
            pollIntervalMs = 1_000L,
        )

        val result = port.tap(ElementSelector(SelectorType.TEXT, "登录"))

        assertTrue(result is CapabilityResult.Failure)
        assertEquals(
            CapabilityFailureCode.STEP_CAPABILITY_UNAVAILABLE,
            (result as CapabilityResult.Failure).errorCode,
        )
    }

    @Test
    fun shouldWaitUntilElementAppearsWithinTimeout() = runBlocking {
        val adapter = ScriptedNodeTreeAdapter(
            responses = listOf(
                null,
                AccessibilityNodeSnapshot(nodeId = "home-tab"),
            ),
        )
        val port = DefaultAccessibilityPort(
            nodeTreeAdapter = adapter,
            pauseController = NoOpPauseController,
            pollIntervalMs = 1_000L,
        )

        val result = port.waitForElement(
            selector = ElementSelector(SelectorType.CONTENT_DESCRIPTION, "首页"),
            state = WaitElementState.APPEARED,
            timeoutMs = 2_000L,
        )

        assertTrue(result is CapabilityResult.Success<*>)
        assertEquals(2, adapter.findCalls)
    }

    @Test
    fun shouldReturnTimeoutWhenElementDoesNotDisappear() = runBlocking {
        val adapter = ScriptedNodeTreeAdapter(
            responses = listOf(
                AccessibilityNodeSnapshot(nodeId = "loading"),
                AccessibilityNodeSnapshot(nodeId = "loading"),
            ),
        )
        val port = DefaultAccessibilityPort(
            nodeTreeAdapter = adapter,
            pauseController = NoOpPauseController,
            pollIntervalMs = 1_000L,
        )

        val result = port.waitForElement(
            selector = ElementSelector(SelectorType.CLASS_NAME, "android.widget.ProgressBar"),
            state = WaitElementState.DISAPPEARED,
            timeoutMs = 2_000L,
        )

        assertTrue(result is CapabilityResult.Failure)
        assertEquals(CapabilityFailureCode.STEP_TIMEOUT, (result as CapabilityResult.Failure).errorCode)
        assertEquals(3, adapter.findCalls)
    }

    private class ScriptedNodeTreeAdapter(
        private val responses: List<AccessibilityNodeSnapshot?>,
        private val available: Boolean = true,
    ) : NodeTreeAdapter {
        var findCalls: Int = 0
        val clickedNodeIds = mutableListOf<String>()

        override fun isAvailable(): Boolean = available

        override suspend fun findFirst(selector: ElementSelector): AccessibilityNodeSnapshot? {
            val index = findCalls.coerceAtMost(responses.lastIndex)
            findCalls += 1
            return responses[index]
        }

        override suspend fun click(node: AccessibilityNodeSnapshot): Boolean {
            clickedNodeIds += node.nodeId
            return true
        }
    }

    private object NoOpPauseController : PauseController {
        override suspend fun pause(durationMs: Long) = Unit
    }
}
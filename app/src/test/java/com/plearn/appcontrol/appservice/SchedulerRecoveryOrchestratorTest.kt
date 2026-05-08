package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.scheduler.SchedulerDispatchMode
import com.plearn.appcontrol.scheduler.SchedulerDispatchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SchedulerRecoveryOrchestratorTest {
    @Test
    fun shouldDispatchRecoveryModeWhenTriggeredByBootCompleted() = runBlocking {
        val recorder = RecordingRecoveryDispatcher()
        val orchestrator = SchedulerRecoveryOrchestrator(
            recoverDispatcher = recorder::dispatch,
        )

        val result = orchestrator.recoverFromBroadcastAction("android.intent.action.BOOT_COMPLETED")

        assertEquals(SchedulerDispatchMode.RECOVERY, recorder.lastMode)
        assertEquals(listOf("task-a"), result?.executedTaskIds)
    }

    @Test
    fun shouldDispatchRecoveryModeWhenTriggeredExplicitly() = runBlocking {
        val recorder = RecordingRecoveryDispatcher()
        val orchestrator = SchedulerRecoveryOrchestrator(
            recoverDispatcher = recorder::dispatch,
        )

        val result = orchestrator.recover(SchedulerRecoveryTrigger.PROCESS_RESTART)

        assertEquals(SchedulerDispatchMode.RECOVERY, recorder.lastMode)
        assertEquals(listOf("task-a"), result.executedTaskIds)
    }

    @Test
    fun shouldIgnoreUnsupportedBroadcastAction() = runBlocking {
        val recorder = RecordingRecoveryDispatcher()
        val orchestrator = SchedulerRecoveryOrchestrator(
            recoverDispatcher = recorder::dispatch,
        )

        val result = orchestrator.recoverFromBroadcastAction("android.intent.action.TIME_SET")

        assertNull(result)
        assertNull(recorder.lastMode)
    }

    @Test
    fun shouldMapSupportedBroadcastActionsToRecoveryTriggers() {
        assertEquals(
            SchedulerRecoveryTrigger.BOOT_COMPLETED,
            SchedulerRecoveryTrigger.fromBroadcastAction("android.intent.action.BOOT_COMPLETED"),
        )
        assertEquals(
            SchedulerRecoveryTrigger.PACKAGE_REPLACED,
            SchedulerRecoveryTrigger.fromBroadcastAction("android.intent.action.MY_PACKAGE_REPLACED"),
        )
        assertNull(SchedulerRecoveryTrigger.fromBroadcastAction("android.intent.action.TIME_SET"))
    }

    private class RecordingRecoveryDispatcher {
        var lastMode: SchedulerDispatchMode? = null

        suspend fun dispatch(mode: SchedulerDispatchMode): SchedulerDispatchResult {
            lastMode = mode
            return SchedulerDispatchResult(executedTaskIds = listOf("task-a"))
        }
    }
}
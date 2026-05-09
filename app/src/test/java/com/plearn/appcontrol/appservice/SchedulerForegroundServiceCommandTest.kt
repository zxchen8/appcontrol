package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.scheduler.SchedulerDispatchMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SchedulerForegroundServiceCommandTest {
    @Test
    fun shouldUseRecoveryDispatchWhenProcessRestartColdStartsService() {
        val command = resolveSchedulerForegroundCommand(
            action = SchedulerRecoveryTrigger.ACTION_PROCESS_RESTART,
            wasRunning = false,
        )

        assertEquals(SchedulerDispatchMode.RECOVERY, command.dispatchMode)
        assertEquals(SchedulerRecoveryTrigger.PROCESS_RESTART, command.recoveryTrigger)
    }

    @Test
    fun shouldUseNormalDispatchWhenProcessRestartArrivesWhileServiceIsRunning() {
        val command = resolveSchedulerForegroundCommand(
            action = SchedulerRecoveryTrigger.ACTION_PROCESS_RESTART,
            wasRunning = true,
        )

        assertEquals(SchedulerDispatchMode.NORMAL, command.dispatchMode)
        assertNull(command.recoveryTrigger)
    }

    @Test
    fun shouldKeepRecoveryDispatchForBootCompletedWhileServiceIsRunning() {
        val command = resolveSchedulerForegroundCommand(
            action = android.content.Intent.ACTION_BOOT_COMPLETED,
            wasRunning = true,
        )

        assertEquals(SchedulerDispatchMode.RECOVERY, command.dispatchMode)
        assertEquals(SchedulerRecoveryTrigger.BOOT_COMPLETED, command.recoveryTrigger)
    }
}
package com.plearn.appcontrol.appservice

import android.content.Intent
import com.plearn.appcontrol.scheduler.SchedulerDispatchMode
import com.plearn.appcontrol.scheduler.SchedulerDispatchResult

enum class SchedulerRecoveryTrigger {
    PROCESS_RESTART,
    BOOT_COMPLETED,
    PACKAGE_REPLACED,
    ;

    companion object {
        fun fromBroadcastAction(action: String?): SchedulerRecoveryTrigger? = when (action) {
            Intent.ACTION_BOOT_COMPLETED -> BOOT_COMPLETED
            Intent.ACTION_MY_PACKAGE_REPLACED -> PACKAGE_REPLACED
            else -> null
        }
    }
}

class SchedulerRecoveryOrchestrator(
    private val recoverDispatcher: suspend (SchedulerDispatchMode) -> SchedulerDispatchResult,
) {
    suspend fun recover(trigger: SchedulerRecoveryTrigger): SchedulerDispatchResult {
        return when (trigger) {
            SchedulerRecoveryTrigger.PROCESS_RESTART,
            SchedulerRecoveryTrigger.BOOT_COMPLETED,
            SchedulerRecoveryTrigger.PACKAGE_REPLACED,
            -> recoverDispatcher(SchedulerDispatchMode.RECOVERY)
        }
    }

    suspend fun recoverFromBroadcastAction(action: String?): SchedulerDispatchResult? {
        val trigger = SchedulerRecoveryTrigger.fromBroadcastAction(action) ?: return null
        return recover(trigger)
    }
}
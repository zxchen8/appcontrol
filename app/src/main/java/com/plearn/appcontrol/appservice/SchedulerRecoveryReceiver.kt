package com.plearn.appcontrol.appservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SchedulerRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = SchedulerRecoveryTrigger.fromBroadcastAction(intent.action) ?: return
        val pendingResult = goAsync()
        SchedulerRecoveryLauncher.launch(context.applicationContext, trigger, pendingResult)
    }
}

internal object SchedulerRecoveryLauncher {
    private val recoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun launch(
        context: Context,
        trigger: SchedulerRecoveryTrigger,
        pendingResult: BroadcastReceiver.PendingResult? = null,
    ) {
        recoveryScope.launch {
            try {
                val entryPoint = EntryPointAccessors.fromApplication(
                    context,
                    SchedulerRecoveryEntryPoint::class.java,
                )
                entryPoint.schedulerRecoveryOrchestrator().recover(trigger)
            } finally {
                pendingResult?.finish()
            }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface SchedulerRecoveryEntryPoint {
    fun schedulerRecoveryOrchestrator(): SchedulerRecoveryOrchestrator
}
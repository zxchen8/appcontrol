package com.plearn.appcontrol.appservice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SchedulerRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val trigger = SchedulerRecoveryTrigger.fromBroadcastAction(intent.action) ?: return
        SchedulerForegroundService.start(context.applicationContext, trigger)
    }
}
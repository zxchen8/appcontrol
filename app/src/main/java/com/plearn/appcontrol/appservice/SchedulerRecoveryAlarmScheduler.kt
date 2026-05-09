package com.plearn.appcontrol.appservice

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

interface SchedulerRecoveryAlarmScheduler {
    fun scheduleProcessRestartRecovery(delayMs: Long = DEFAULT_RECOVERY_DELAY_MS)

    fun cancelRecovery()

    companion object {
        const val DEFAULT_RECOVERY_DELAY_MS = 60_000L
    }
}

class AlarmManagerSchedulerRecoveryAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) : SchedulerRecoveryAlarmScheduler {
    override fun scheduleProcessRestartRecovery(delayMs: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + delayMs,
            recoveryPendingIntent(),
        )
    }

    override fun cancelRecovery() {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pendingIntent = recoveryPendingIntent()
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    private fun recoveryPendingIntent(): PendingIntent {
        val intent = Intent(context, SchedulerRecoveryReceiver::class.java)
            .setPackage(context.packageName)
            .setAction(SchedulerRecoveryTrigger.ACTION_PROCESS_RESTART)
        return PendingIntent.getBroadcast(
            context,
            RECOVERY_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val RECOVERY_REQUEST_CODE = 1001
    }
}
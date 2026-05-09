package com.plearn.appcontrol.appservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.plearn.appcontrol.MainActivity
import com.plearn.appcontrol.R
import com.plearn.appcontrol.scheduler.SchedulerDispatchMode
import com.plearn.appcontrol.scheduler.SchedulerTimeSource
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class SchedulerForegroundService : Service() {
    @Inject
    lateinit var runtimeCoordinator: SchedulerForegroundRuntimeCoordinator

    @Inject
    lateinit var schedulerTimeSource: SchedulerTimeSource

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runtimeJob: Job? = null
    private var stopRequested: Boolean = false
    private var standbyActive: Boolean = false

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            standbyActive = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val command = resolveSchedulerForegroundCommand(
            action = intent?.action,
            wasRunning = SchedulerForegroundRuntimeState.running,
        )
        SchedulerForegroundRuntimeState.running = true

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(
                contentText = getString(R.string.scheduler_notification_recovering),
            ),
            schedulerForegroundServiceType(),
        )

        if (runtimeJob?.isActive == true) {
            return START_STICKY
        }

        runtimeJob = serviceScope.launch {
            var runtimeResult = when (command.dispatchMode) {
                SchedulerDispatchMode.NORMAL -> runtimeCoordinator.dispatchStandby()
                SchedulerDispatchMode.RECOVERY -> runtimeCoordinator.start(
                    command.recoveryTrigger ?: SchedulerRecoveryTrigger.PROCESS_RESTART,
                )
            }
            while (true) {
                standbyActive = runtimeResult.keepRunning
                if (!runtimeResult.keepRunning) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }

                val dispatchedCount = runtimeResult.dispatchResult?.executedTaskIds?.size ?: 0
                updateNotification(
                    contentText = getString(
                        R.string.scheduler_notification_running,
                        runtimeResult.activeScheduleCount,
                        dispatchedCount,
                    ),
                )

                val nextDelayMs = runtimeCoordinator.nextStandbyDelayMs()
                if (nextDelayMs == null) {
                    standbyActive = false
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@launch
                }

                schedulerTimeSource.delay(nextDelayMs)
                if (stopRequested) {
                    return@launch
                }

                runtimeResult = runtimeCoordinator.dispatchStandby()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        SchedulerForegroundRuntimeState.running = false
        runtimeJob = null
        serviceScope.cancel()
        runtimeCoordinator.onServiceStopped(stopRequested = stopRequested, standbyActive = standbyActive)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun updateNotification(contentText: String) {
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return
        notificationManager.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    private fun buildNotification(contentText: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            CONTENT_REQUEST_CODE,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            STOP_REQUEST_CODE,
            Intent(this, SchedulerForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle(getString(R.string.scheduler_notification_title))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(
                0,
                getString(R.string.scheduler_notification_stop),
                stopIntent,
            )
            .build()
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val notificationManager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.scheduler_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.scheduler_notification_channel_description)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun schedulerForegroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }

    companion object {
        private const val CHANNEL_ID = "scheduler_runtime"
        private const val NOTIFICATION_ID = 1002
        private const val CONTENT_REQUEST_CODE = 1003
        private const val STOP_REQUEST_CODE = 1004
        const val ACTION_STOP = "com.plearn.appcontrol.action.SCHEDULER_STOP"

        fun start(context: Context, trigger: SchedulerRecoveryTrigger) {
            val intent = Intent(context, SchedulerForegroundService::class.java)
                .setAction(trigger.toServiceAction())
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SchedulerForegroundService::class.java)
                .setAction(ACTION_STOP)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

internal data class SchedulerForegroundCommand(
    val dispatchMode: SchedulerDispatchMode,
    val recoveryTrigger: SchedulerRecoveryTrigger? = null,
)

internal fun resolveSchedulerForegroundCommand(
    action: String?,
    wasRunning: Boolean,
): SchedulerForegroundCommand = when {
    wasRunning && action == SchedulerRecoveryTrigger.ACTION_PROCESS_RESTART -> SchedulerForegroundCommand(
        dispatchMode = SchedulerDispatchMode.NORMAL,
    )

    else -> SchedulerForegroundCommand(
        dispatchMode = SchedulerDispatchMode.RECOVERY,
        recoveryTrigger = SchedulerRecoveryTrigger.fromServiceAction(action) ?: SchedulerRecoveryTrigger.PROCESS_RESTART,
    )
}
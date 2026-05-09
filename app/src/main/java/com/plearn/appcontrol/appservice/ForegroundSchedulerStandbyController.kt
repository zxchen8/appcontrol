package com.plearn.appcontrol.appservice

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

class ForegroundSchedulerStandbyController @Inject constructor(
    @ApplicationContext private val context: Context,
) : SchedulerStandbyController {
    override fun syncActiveSchedules(hasActiveSchedules: Boolean) {
        if (hasActiveSchedules) {
            SchedulerForegroundService.start(context, SchedulerRecoveryTrigger.PROCESS_RESTART)
        } else {
            SchedulerForegroundService.stop(context)
        }
    }
}
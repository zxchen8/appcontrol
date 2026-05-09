package com.plearn.appcontrol.appservice

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchedulerRecoveryReceiverTest {
    private lateinit var application: Application
    private lateinit var alarmManager: AlarmManager
    private lateinit var receiver: SchedulerRecoveryReceiver

    @Before
    fun setUp() {
        application = ApplicationProvider.getApplicationContext()
        alarmManager = application.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        receiver = SchedulerRecoveryReceiver()
        SchedulerForegroundRuntimeState.running = false
        shadowOf(application).clearStartedServices()
    }

    @After
    fun tearDown() {
        SchedulerForegroundRuntimeState.running = false
        shadowOf(application).clearStartedServices()
    }

    @Test
    fun shouldStartForegroundServiceWhenProcessRestartReceivedWhileRuntimeRunning() {
        SchedulerForegroundRuntimeState.running = true

        receiver.onReceive(
            application,
            Intent(SchedulerRecoveryTrigger.ACTION_PROCESS_RESTART),
        )

        val startedService = shadowOf(application).peekNextStartedService()
        assertNotNull(startedService)
        assertEquals(SchedulerForegroundService::class.java.name, startedService!!.component?.className)
        assertEquals(SchedulerRecoveryTrigger.ACTION_PROCESS_RESTART, startedService.action)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun shouldStartForegroundServiceWithProcessRestartActionWhenProcessRestartReceivedWhileRuntimeNotRunning() {
        receiver.onReceive(
            application,
            Intent(SchedulerRecoveryTrigger.ACTION_PROCESS_RESTART),
        )

        val startedService = shadowOf(application).peekNextStartedService()
        assertNotNull(startedService)
        assertEquals(SchedulerForegroundService::class.java.name, startedService!!.component?.className)
        assertEquals(SchedulerRecoveryTrigger.ACTION_PROCESS_RESTART, startedService.action)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }

    @Test
    fun shouldStartForegroundServiceForBootCompletedEvenWhenRuntimeRunning() {
        SchedulerForegroundRuntimeState.running = true

        receiver.onReceive(
            application,
            Intent(Intent.ACTION_BOOT_COMPLETED),
        )

        val startedService = shadowOf(application).peekNextStartedService()
        assertNotNull(startedService)
        assertEquals(SchedulerForegroundService::class.java.name, startedService!!.component?.className)
        assertEquals(Intent.ACTION_BOOT_COMPLETED, startedService.action)
        assertNull(shadowOf(alarmManager).peekNextScheduledAlarm())
    }
}
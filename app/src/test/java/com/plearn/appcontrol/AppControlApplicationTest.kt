package com.plearn.appcontrol

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.plearn.appcontrol.diagnostics.DiagnosticsRetentionStartupCleaner
import com.plearn.appcontrol.di.DataModule
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppControlApplicationTest {
    @After
    fun tearDown() {
        DataModule.diagnosticsRetentionStartupCleanerOverride = null
    }

    @Test
    fun shouldScheduleDiagnosticsCleanupWhenApplicationStarts() {
        var cleanupCount = 0
        var reportedError: Throwable? = null
        val cleaner = DiagnosticsRetentionStartupCleaner(
            cleanup = { cleanupCount += 1 },
            launchCleanup = { block -> runBlocking { block() } },
            onError = { reportedError = it },
        )
        DataModule.diagnosticsRetentionStartupCleanerOverride = cleaner

        val application = AppControlApplication()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(application, ApplicationProvider.getApplicationContext<Context>())

        application.onCreate()

        assertEquals(1, cleanupCount)
        assertNull(reportedError)
    }
}
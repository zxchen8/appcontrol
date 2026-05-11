package com.plearn.appcontrol.diagnostics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DiagnosticsRetentionStartupCleanerTest {
    @Test
    fun shouldRunCleanupWhenScheduled() {
        var cleanupCount = 0
        var reportedError: Throwable? = null
        val cleaner = DiagnosticsRetentionStartupCleaner(
            cleanup = { cleanupCount += 1 },
            launchCleanup = { block -> runBlocking { block() } },
            onError = { reportedError = it },
        )

        cleaner.scheduleCleanup()

        assertEquals(1, cleanupCount)
        assertNull(reportedError)
    }

    @Test
    fun shouldReportCleanupFailureWithoutThrowing() {
        var reportedError: Throwable? = null
        val cleaner = DiagnosticsRetentionStartupCleaner(
            cleanup = { throw IllegalStateException("cleanup failed") },
            launchCleanup = { block -> runBlocking { block() } },
            onError = { reportedError = it },
        )

        cleaner.scheduleCleanup()

        assertEquals("cleanup failed", reportedError?.message)
    }
}
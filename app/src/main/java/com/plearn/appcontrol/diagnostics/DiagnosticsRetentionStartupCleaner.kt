package com.plearn.appcontrol.diagnostics

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class DiagnosticsRetentionStartupCleaner(
    private val cleanup: suspend () -> Unit,
    private val launchCleanup: (suspend () -> Unit) -> Unit = { block ->
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            block()
        }
    },
    private val onError: (Throwable) -> Unit = { error ->
        Log.w(TAG, "Failed to prune retained diagnostics on startup.", error)
    },
) {
    fun scheduleCleanup() {
        launchCleanup {
            try {
                cleanup()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                onError(error)
            }
        }
    }

    private companion object {
        const val TAG = "DiagRetentionCleaner"
    }
}
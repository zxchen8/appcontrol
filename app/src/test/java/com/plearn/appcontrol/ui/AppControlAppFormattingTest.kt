package com.plearn.appcontrol.ui

import com.plearn.appcontrol.appservice.DeviceEnvironmentReport
import com.plearn.appcontrol.appservice.DeviceValidationResult
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.runner.TaskExecutionResult
import com.plearn.appcontrol.runner.TaskRunStatus
import org.junit.Assert.assertTrue
import org.junit.Test

class AppControlAppFormattingTest {
    @Test
    fun shouldFormatCancelledSmokeCheckExecutionAsCancelled() {
        val result = DeviceValidationResult(
            environment = DeviceEnvironmentReport(
                rootReady = true,
                accessibilityEnabled = true,
                accessibilityConnected = true,
                foregroundPackageName = "com.android.launcher3",
            ),
            execution = TaskExecutionResult(
                taskRun = TaskRunRecord(
                    runId = "run-smoke",
                    sessionId = null,
                    cycleNo = null,
                    taskId = "device-validation-smoke",
                    credentialSetId = null,
                    credentialProfileId = null,
                    credentialAlias = null,
                    status = TaskRunStatus.CANCELLED,
                    startedAt = 1L,
                    finishedAt = 2L,
                    durationMs = 1L,
                    triggerType = "manual",
                    errorCode = null,
                    message = null,
                ),
                stepRuns = emptyList(),
                taskAttemptCount = 1,
            ),
        )

        val text = formatDeviceValidationResult(result)

        assertTrue(text.contains("Smoke check cancelled: cancelled"))
    }
}
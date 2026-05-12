package com.plearn.appcontrol.ui

import com.plearn.appcontrol.appservice.DeviceEnvironmentReport
import com.plearn.appcontrol.appservice.DeviceValidationErrorCode
import com.plearn.appcontrol.appservice.DeviceValidationResult
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.runner.TaskExecutionResult
import com.plearn.appcontrol.runner.TaskRunStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AppControlAppFormattingTest {
    @Test
    fun shouldFormatSuccessfulSmokeCheckExecutionAsSucceeded() {
        val text = formatDeviceValidationResult(resultWithExecutionStatus(TaskRunStatus.SUCCESS))

        assertStatusLineEquals(text, "Smoke check succeeded: success")
    }

    @Test
    fun shouldFormatBlockedSmokeCheckExecutionAsBlocked() {
        val text = formatDeviceValidationResult(resultWithExecutionStatus(TaskRunStatus.BLOCKED))

        assertStatusLineEquals(text, "Smoke check blocked: blocked")
    }

    @Test
    fun shouldFormatCancelledSmokeCheckExecutionAsCancelled() {
        val text = formatDeviceValidationResult(resultWithExecutionStatus(TaskRunStatus.CANCELLED))

        assertStatusLineEquals(text, "Smoke check cancelled: cancelled")
    }

    @Test
    fun shouldFormatFailedSmokeCheckExecutionAsFailed() {
        val text = formatDeviceValidationResult(resultWithExecutionStatus(TaskRunStatus.FAILED))

        assertStatusLineEquals(text, "Smoke check failed: failed")
    }

    @Test
    fun shouldFormatTimedOutSmokeCheckExecutionAsTimedOut() {
        val text = formatDeviceValidationResult(resultWithExecutionStatus(TaskRunStatus.TIMED_OUT))

        assertStatusLineEquals(text, "Smoke check timed out: timed_out")
    }

    @Test
    fun shouldFormatUnknownSmokeCheckExecutionStatusAsGenericResult() {
        val text = formatDeviceValidationResult(resultWithExecutionStatus("degraded"))

        assertStatusLineEquals(text, "Smoke check result: degraded")
    }

    @Test
    fun shouldFormatMissingSmokeCheckExecutionAndErrorCodeAsUnknown() {
        val text = formatDeviceValidationResult(
            DeviceValidationResult(
                environment = environmentReport(),
                execution = null,
                errorCode = null,
                message = null,
            ),
        )

        assertStatusLineEquals(text, "Smoke check result: unknown")
    }

    @Test
    fun shouldFormatRootValidationErrorAsBlocked() {
        val text = formatDeviceValidationResult(
            DeviceValidationResult(
                environment = environmentReport(rootReady = false),
                execution = null,
                errorCode = DeviceValidationErrorCode.ROOT_NOT_READY,
                message = "Root shell is not available.",
            ),
        )

        assertStatusLineEquals(
            text,
            "Smoke check blocked: ROOT_NOT_READY - Root shell is not available.",
            expectedRootReady = false,
        )
    }

    @Test
    fun shouldFormatExecutionExceptionSmokeCheckErrorAsFailed() {
        val text = formatDeviceValidationResult(
            DeviceValidationResult(
                environment = environmentReport(),
                execution = null,
                errorCode = DeviceValidationErrorCode.SMOKE_CHECK_EXECUTION_EXCEPTION,
                message = "Smoke check failed with an execution exception.",
            ),
        )

        assertStatusLineEquals(
            text,
            "Smoke check failed: SMOKE_CHECK_EXECUTION_EXCEPTION - Smoke check failed with an execution exception.",
        )
    }

    @Test
    fun shouldFormatEnvironmentWithNotificationDisabledPackageMissingAndTimezoneMismatch() {
        val text = formatDeviceValidationResult(
            DeviceValidationResult(
                environment = environmentReport(
                    notificationsEnabled = false,
                    targetPackageName = "com.example.target",
                    targetPackageInstalled = false,
                    deviceTimezoneId = "Europe/London",
                    sampleTimezoneAligned = false,
                ),
                execution = null,
                errorCode = null,
                message = null,
            ),
        )

        assertEquals(
            """
            Root: ready
            Accessibility enabled: true
            Accessibility connected: true
            Foreground package: com.android.launcher3
            Notifications enabled: false
            Target package: com.example.target
            Target package installed: false
            Device timezone: Europe/London
            Sample timezone: Asia/Shanghai
            Timezone aligned: false
            Smoke check result: unknown
            """.trimIndent(),
            text,
        )
    }

    private fun assertStatusLineEquals(
        text: String,
        expected: String,
        expectedRootReady: Boolean = true,
    ) {
        assertEquals(
            """
            Root: ${if (expectedRootReady) "ready" else "missing"}
            Accessibility enabled: true
            Accessibility connected: true
            Foreground package: com.android.launcher3
            Notifications enabled: true
            Target package: com.example.target
            Target package installed: true
            Device timezone: Asia/Shanghai
            Sample timezone: Asia/Shanghai
            Timezone aligned: true
            $expected
            """.trimIndent(),
            text,
        )
    }

    private fun resultWithExecutionStatus(status: String): DeviceValidationResult = DeviceValidationResult(
        environment = environmentReport(),
        execution = TaskExecutionResult(
            taskRun = TaskRunRecord(
                runId = "run-smoke",
                sessionId = null,
                cycleNo = null,
                taskId = "device-validation-smoke",
                credentialSetId = null,
                credentialProfileId = null,
                credentialAlias = null,
                status = status,
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

    private fun environmentReport(
        rootReady: Boolean = true,
        notificationsEnabled: Boolean = true,
        targetPackageName: String? = "com.example.target",
        targetPackageInstalled: Boolean? = true,
        deviceTimezoneId: String = "Asia/Shanghai",
        sampleTimezoneId: String = "Asia/Shanghai",
        sampleTimezoneAligned: Boolean = deviceTimezoneId == sampleTimezoneId,
    ): DeviceEnvironmentReport = DeviceEnvironmentReport(
        rootReady = rootReady,
        accessibilityEnabled = true,
        accessibilityConnected = true,
        foregroundPackageName = "com.android.launcher3",
        notificationsEnabled = notificationsEnabled,
        targetPackageName = targetPackageName,
        targetPackageInstalled = targetPackageInstalled,
        deviceTimezoneId = deviceTimezoneId,
        sampleTimezoneId = sampleTimezoneId,
        sampleTimezoneAligned = sampleTimezoneAligned,
    )
}
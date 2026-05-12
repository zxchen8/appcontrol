package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.capability.ElementSelector
import com.plearn.appcontrol.capability.SelectorType
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.dsl.TaskDefinition
import com.plearn.appcontrol.runner.RunTriggerType
import com.plearn.appcontrol.runner.TaskExecutionResult
import com.plearn.appcontrol.runner.TaskRunStatus
import com.plearn.appcontrol.runner.TaskRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DeviceValidationServiceTest {
    @Test
    fun shouldPassCurrentPackageNameToInspectEnvironment() = runBlocking {
        val inspector = RecordingDeviceEnvironmentInspector(
            report = environmentReport(
                targetPackageName = "com.example.target",
                targetPackageInstalled = true,
            ),
        )
        val service = DeviceValidationService(
            environmentInspector = inspector,
            taskRunner = RecordingTaskRunner(),
        )

        val result = service.inspectEnvironment(packageName = "com.example.target")

        assertEquals("com.example.target", inspector.lastTargetPackageName)
        assertEquals("com.example.target", result.targetPackageName)
        assertTrue(result.targetPackageInstalled == true)
    }

    @Test
    fun shouldBlockSmokeCheckWhenAccessibilityServiceIsNotConnected() = runBlocking {
        val runner = RecordingTaskRunner()
        val service = DeviceValidationService(
            environmentInspector = FixedDeviceEnvironmentInspector(
                environmentReport(
                    accessibilityConnected = false,
                    foregroundPackageName = "com.example.target",
                    targetPackageName = "com.example.target",
                    targetPackageInstalled = true,
                ),
            ),
            taskRunner = runner,
        )

        val result = service.runTapSmokeCheck(
            TapSmokeCheckRequest(
                packageName = "com.example.target",
                selector = ElementSelector(SelectorType.TEXT, "登录"),
            ),
        )

        assertEquals(DeviceValidationErrorCode.ACCESSIBILITY_NOT_CONNECTED, result.errorCode)
        assertNull(result.execution)
        assertEquals(0, runner.invocationCount)
    }

    @Test
    fun shouldBuildSmokeTaskAndExecuteViaRunnerWhenEnvironmentIsReady() = runBlocking {
        val runner = RecordingTaskRunner()
        val service = DeviceValidationService(
            environmentInspector = FixedDeviceEnvironmentInspector(
                environmentReport(
                    foregroundPackageName = "com.android.launcher3",
                    targetPackageName = "com.example.target",
                    targetPackageInstalled = true,
                ),
            ),
            taskRunner = runner,
        )

        val result = service.runTapSmokeCheck(
            TapSmokeCheckRequest(
                packageName = "com.example.target",
                selector = ElementSelector(SelectorType.RESOURCE_ID, "com.example.target:id/login_button"),
            ),
        )

        assertEquals(1, runner.invocationCount)
        assertEquals(RunTriggerType.MANUAL, runner.lastTriggerType)
        assertTrue(runner.lastTask!!.steps.map { it.type.name }.containsAll(listOf("START_APP", "WAIT_ELEMENT", "TAP")))
        assertEquals(TaskRunStatus.SUCCESS, result.execution!!.taskRun.status)
        assertEquals("com.android.launcher3", result.environment.foregroundPackageName)
        assertEquals("com.example.target", result.environment.targetPackageName)
        assertTrue(result.environment.targetPackageInstalled == true)
    }

    @Test
    fun shouldReturnStructuredErrorWhenSmokeCheckRunnerThrowsException() = runBlocking {
        val runner = RecordingTaskRunner(shouldThrow = true)
        val service = DeviceValidationService(
            environmentInspector = FixedDeviceEnvironmentInspector(
                environmentReport(
                    foregroundPackageName = "com.android.launcher3",
                    targetPackageName = "com.example.target",
                    targetPackageInstalled = true,
                ),
            ),
            taskRunner = runner,
        )

        val result = service.runTapSmokeCheck(
            TapSmokeCheckRequest(
                packageName = "com.example.target",
                selector = ElementSelector(SelectorType.TEXT, "登录"),
            ),
        )

        assertEquals(1, runner.invocationCount)
        assertNull(result.execution)
        assertEquals(DeviceValidationErrorCode.SMOKE_CHECK_EXECUTION_EXCEPTION, result.errorCode)
        assertEquals("Smoke check failed with an execution exception.", result.message)
        assertEquals("com.android.launcher3", result.environment.foregroundPackageName)
    }

    @Test
    fun shouldPropagateCancellationWhenSmokeCheckRunnerIsCancelled() = runBlocking {
        val runner = RecordingTaskRunner(shouldCancel = true)
        val service = DeviceValidationService(
            environmentInspector = FixedDeviceEnvironmentInspector(
                environmentReport(
                    foregroundPackageName = "com.android.launcher3",
                    targetPackageName = "com.example.target",
                    targetPackageInstalled = true,
                ),
            ),
            taskRunner = runner,
        )

        try {
            service.runTapSmokeCheck(
                TapSmokeCheckRequest(
                    packageName = "com.example.target",
                    selector = ElementSelector(SelectorType.TEXT, "登录"),
                ),
            )
            fail("Expected cancellation to be propagated.")
        } catch (error: CancellationException) {
            assertEquals("runner validation cancelled", error.message)
        }

        assertEquals(1, runner.invocationCount)
    }

    @Test
    fun shouldReturnCancelledExecutionWhenSmokeCheckRunnerReportsCancelledResult() = runBlocking {
        val runner = RecordingTaskRunner(resultStatus = TaskRunStatus.CANCELLED)
        val service = DeviceValidationService(
            environmentInspector = FixedDeviceEnvironmentInspector(
                environmentReport(
                    foregroundPackageName = "com.android.launcher3",
                    targetPackageName = "com.example.target",
                    targetPackageInstalled = true,
                ),
            ),
            taskRunner = runner,
        )

        val result = service.runTapSmokeCheck(
            TapSmokeCheckRequest(
                packageName = "com.example.target",
                selector = ElementSelector(SelectorType.TEXT, "登录"),
            ),
        )

        assertEquals(1, runner.invocationCount)
        assertEquals(TaskRunStatus.CANCELLED, result.execution?.taskRun?.status)
        assertNull(result.errorCode)
    }

    @Test
    fun shouldInspectRootAccessibilityForegroundNotificationPackageAndTimezone() = runBlocking {
        val inspector = DefaultDeviceEnvironmentInspector(
            rootShellPort = FixedRootShellPort(
                mutableMapOf(
                    "id" to ShellSnapshot(exitCode = 0, stdout = "uid=0(root)"),
                    DefaultDeviceEnvironmentInspector.FOREGROUND_PACKAGE_COMMAND to ShellSnapshot(
                        exitCode = 0,
                        stdout = "mCurrentFocus=Window{123 u0 com.example.target/com.example.target.MainActivity}",
                    ),
                ),
            ),
            accessibilitySettingsPort = FixedAccessibilitySettingsPort(enabled = true),
            accessibilityConnectionPort = FixedAccessibilityConnectionPort(connected = true),
            notificationStatusPort = FixedNotificationStatusPort(enabled = true),
            targetPackageStatusPort = FixedTargetPackageStatusPort(installedPackages = setOf("com.example.target")),
            deviceTimeZonePort = FixedDeviceTimeZonePort(timeZoneId = "Asia/Shanghai"),
        )

        val result = inspector.inspect(targetPackageName = "com.example.target")

        assertTrue(result.rootReady)
        assertTrue(result.accessibilityEnabled)
        assertTrue(result.accessibilityConnected)
        assertEquals("com.example.target", result.foregroundPackageName)
        assertTrue(result.notificationsEnabled)
        assertEquals("com.example.target", result.targetPackageName)
        assertTrue(result.targetPackageInstalled == true)
        assertEquals("Asia/Shanghai", result.deviceTimezoneId)
        assertEquals("Asia/Shanghai", result.sampleTimezoneId)
        assertTrue(result.sampleTimezoneAligned)
    }

    @Test
    fun shouldInspectEnvironmentReportNotificationDisabledPackageMissingAndTimezoneMismatch() = runBlocking {
        val inspector = DefaultDeviceEnvironmentInspector(
            rootShellPort = FixedRootShellPort(
                mutableMapOf(
                    "id" to ShellSnapshot(exitCode = 0, stdout = "uid=0(root)"),
                    DefaultDeviceEnvironmentInspector.FOREGROUND_PACKAGE_COMMAND to ShellSnapshot(
                        exitCode = 0,
                        stdout = "mCurrentFocus=Window{123 u0 com.android.launcher3/com.android.launcher3.Launcher}",
                    ),
                ),
            ),
            accessibilitySettingsPort = FixedAccessibilitySettingsPort(enabled = true),
            accessibilityConnectionPort = FixedAccessibilityConnectionPort(connected = true),
            notificationStatusPort = FixedNotificationStatusPort(enabled = false),
            targetPackageStatusPort = FixedTargetPackageStatusPort(installedPackages = emptySet()),
            deviceTimeZonePort = FixedDeviceTimeZonePort(timeZoneId = "Europe/London"),
        )

        val result = inspector.inspect(targetPackageName = "com.example.target")

        assertEquals("com.example.target", result.targetPackageName)
        assertTrue(result.targetPackageInstalled == false)
        assertTrue(!result.notificationsEnabled)
        assertEquals("Europe/London", result.deviceTimezoneId)
        assertEquals("Asia/Shanghai", result.sampleTimezoneId)
        assertTrue(!result.sampleTimezoneAligned)
    }

    private class RecordingTaskRunner(
        private val shouldThrow: Boolean = false,
        private val shouldCancel: Boolean = false,
        private val resultStatus: String = TaskRunStatus.SUCCESS,
    ) : TaskRunner {
        var invocationCount: Int = 0
        var lastTask: TaskDefinition? = null
        var lastTriggerType: String? = null

        override suspend fun run(task: TaskDefinition, triggerType: String): TaskExecutionResult {
            invocationCount += 1
            lastTask = task
            lastTriggerType = triggerType
            if (shouldCancel) {
                throw CancellationException("runner validation cancelled")
            }
            if (shouldThrow) {
                throw IllegalStateException("runner validation failed")
            }
            return TaskExecutionResult(
                taskRun = TaskRunRecord(
                    runId = "run-smoke",
                    sessionId = null,
                    cycleNo = null,
                    taskId = task.taskId,
                    credentialSetId = null,
                    credentialProfileId = null,
                    credentialAlias = null,
                    status = resultStatus,
                    startedAt = 1L,
                    finishedAt = 2L,
                    durationMs = 1L,
                    triggerType = triggerType,
                    errorCode = null,
                    message = null,
                ),
                stepRuns = emptyList(),
                taskAttemptCount = 1,
            )
        }
    }

    private class FixedDeviceEnvironmentInspector(
        private val report: DeviceEnvironmentReport,
    ) : DeviceEnvironmentInspector {
        override suspend fun inspect(targetPackageName: String?): DeviceEnvironmentReport = report
    }

    private class RecordingDeviceEnvironmentInspector(
        private val report: DeviceEnvironmentReport,
    ) : DeviceEnvironmentInspector {
        var lastTargetPackageName: String? = null

        override suspend fun inspect(targetPackageName: String?): DeviceEnvironmentReport {
            lastTargetPackageName = targetPackageName
            return report.copy(targetPackageName = targetPackageName)
        }
    }

    private class FixedRootShellPort(
        private val snapshots: Map<String, ShellSnapshot>,
    ) : EnvironmentRootShellPort {
        override suspend fun run(command: String): ShellSnapshot = snapshots.getValue(command)
    }

    private class FixedAccessibilitySettingsPort(
        private val enabled: Boolean,
    ) : AccessibilitySettingsPort {
        override fun isServiceEnabled(): Boolean = enabled
    }

    private class FixedAccessibilityConnectionPort(
        private val connected: Boolean,
    ) : AccessibilityConnectionPort {
        override fun isServiceConnected(): Boolean = connected
    }

    private class FixedNotificationStatusPort(
        private val enabled: Boolean,
    ) : NotificationStatusPort {
        override fun areNotificationsEnabled(): Boolean = enabled
    }

    private class FixedTargetPackageStatusPort(
        private val installedPackages: Set<String>,
    ) : TargetPackageStatusPort {
        override fun isInstalled(packageName: String): Boolean = packageName in installedPackages
    }

    private class FixedDeviceTimeZonePort(
        private val timeZoneId: String,
    ) : DeviceTimeZonePort {
        override fun systemTimeZoneId(): String = timeZoneId
    }

    private fun environmentReport(
        rootReady: Boolean = true,
        accessibilityEnabled: Boolean = true,
        accessibilityConnected: Boolean = true,
        foregroundPackageName: String? = "com.android.launcher3",
        notificationsEnabled: Boolean = true,
        targetPackageName: String? = null,
        targetPackageInstalled: Boolean? = null,
        deviceTimezoneId: String = "Asia/Shanghai",
        sampleTimezoneId: String = "Asia/Shanghai",
        sampleTimezoneAligned: Boolean = deviceTimezoneId == sampleTimezoneId,
    ): DeviceEnvironmentReport = DeviceEnvironmentReport(
        rootReady = rootReady,
        accessibilityEnabled = accessibilityEnabled,
        accessibilityConnected = accessibilityConnected,
        foregroundPackageName = foregroundPackageName,
        notificationsEnabled = notificationsEnabled,
        targetPackageName = targetPackageName,
        targetPackageInstalled = targetPackageInstalled,
        deviceTimezoneId = deviceTimezoneId,
        sampleTimezoneId = sampleTimezoneId,
        sampleTimezoneAligned = sampleTimezoneAligned,
    )
}

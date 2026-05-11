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
    fun shouldBlockSmokeCheckWhenAccessibilityServiceIsNotConnected() = runBlocking {
        val runner = RecordingTaskRunner()
        val service = DeviceValidationService(
            environmentInspector = FixedDeviceEnvironmentInspector(
                DeviceEnvironmentReport(
                    rootReady = true,
                    accessibilityEnabled = true,
                    accessibilityConnected = false,
                    foregroundPackageName = "com.example.target",
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
                DeviceEnvironmentReport(
                    rootReady = true,
                    accessibilityEnabled = true,
                    accessibilityConnected = true,
                    foregroundPackageName = "com.android.launcher3",
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
    }

    @Test
    fun shouldReturnStructuredErrorWhenSmokeCheckRunnerThrowsException() = runBlocking {
        val runner = RecordingTaskRunner(shouldThrow = true)
        val service = DeviceValidationService(
            environmentInspector = FixedDeviceEnvironmentInspector(
                DeviceEnvironmentReport(
                    rootReady = true,
                    accessibilityEnabled = true,
                    accessibilityConnected = true,
                    foregroundPackageName = "com.android.launcher3",
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
                DeviceEnvironmentReport(
                    rootReady = true,
                    accessibilityEnabled = true,
                    accessibilityConnected = true,
                    foregroundPackageName = "com.android.launcher3",
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
                DeviceEnvironmentReport(
                    rootReady = true,
                    accessibilityEnabled = true,
                    accessibilityConnected = true,
                    foregroundPackageName = "com.android.launcher3",
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
    fun shouldInspectRootAccessibilityAndForegroundPackage() = runBlocking {
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
        )

        val result = inspector.inspect()

        assertTrue(result.rootReady)
        assertTrue(result.accessibilityEnabled)
        assertTrue(result.accessibilityConnected)
        assertEquals("com.example.target", result.foregroundPackageName)
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
        override suspend fun inspect(): DeviceEnvironmentReport = report
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
}

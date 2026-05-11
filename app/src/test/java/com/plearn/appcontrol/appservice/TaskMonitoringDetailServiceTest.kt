package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.data.model.ContinuousSessionRecord
import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.data.model.TaskScheduleStateRecord
import com.plearn.appcontrol.data.repository.RunRecordRepository
import com.plearn.appcontrol.data.repository.SessionRepository
import com.plearn.appcontrol.data.repository.TaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TaskMonitoringDetailServiceTest {
    @Test
    fun shouldLoadLatestRunAndStepRunsByDefault() = runBlocking {
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(
                definition = taskDefinitionRecord,
                scheduleState = taskScheduleStateRecord,
            ),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf(
                    "task-a" to listOf(newerRun, olderRun),
                ),
                stepRunsByRunId = mapOf(
                    "run-newer" to listOf(stepRun1, stepRun2),
                ),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = null, recentRunLimit = 5)

        assertNotNull(snapshot)
        assertEquals("task-a", snapshot?.definition?.taskId)
        assertEquals(true, snapshot?.diagnostics?.captureScreenshotOnFailure)
        assertEquals(5_000L, snapshot?.scheduleState?.nextTriggerAt)
        assertEquals("账号A", snapshot?.runningSession?.currentCredentialAlias)
        assertEquals(listOf("run-newer", "run-older"), snapshot?.recentRuns?.map { it.runId })
        assertEquals("run-newer", snapshot?.selectedRun?.runId)
        assertEquals("RUNNER_STEP_FAILED", snapshot?.failureContext?.runErrorCode)
        assertEquals(listOf("step-1", "step-2"), snapshot?.stepRuns?.map { it.stepId })
    }

    @Test
    fun shouldExposeDiagnosticsPolicyAndArtifactsForFailedSelectedRun() = runBlocking {
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionWithDiagnostics, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(newerRun)),
                stepRunsByRunId = mapOf(
                    "run-newer" to listOf(
                        stepRun1,
                        stepRun2.copy(artifactsJson = "{\"artifactType\":\"screenshot_suppressed\",\"reason\":\"sensitive\"}"),
                    ),
                ),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-newer", recentRunLimit = 5)

        assertEquals(false, snapshot?.diagnostics?.captureScreenshotOnFailure)
        assertEquals(false, snapshot?.diagnostics?.captureScreenshotOnStepFailure)
        assertEquals("debug", snapshot?.diagnostics?.logLevel)
        assertEquals("run-newer", snapshot?.failureContext?.runId)
        assertEquals("run newer failed", snapshot?.failureContext?.runMessage)
        assertEquals(1, snapshot?.failureContext?.failedStepCount)
        assertEquals("step-2", snapshot?.failureContext?.primaryFailedStepId)
        assertEquals(emptyList<String>(), snapshot?.failureContext?.runArtifacts)
        assertEquals(
            listOf("step-2=截图已抑制 | 原因=sensitive"),
            snapshot?.failureContext?.stepArtifacts,
        )
    }

    @Test
    fun shouldFormatStructuredRunnerArtifactReasonsForFailureContext() = runBlocking {
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionWithDiagnostics, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(newerRun)),
                stepRunsByRunId = mapOf(
                    "run-newer" to listOf(
                        stepRun1,
                        stepRun2.copy(
                            artifactsJson = "{\"artifactType\":\"screenshot_suppressed\",\"reason\":\"DIAG_SCREENSHOT_SUPPRESSED_FOR_SENSITIVE_CONTENT\",\"captureRequested\":true,\"sensitiveContextActive\":true}",
                        ),
                    ),
                ),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-newer", recentRunLimit = 5)

        assertEquals(
            listOf(
                "step-2=截图已抑制 | 原因=敏感内容，禁止采集 (DIAG_SCREENSHOT_SUPPRESSED_FOR_SENSITIVE_CONTENT) | 请求截图=true | 敏感上下文=true",
            ),
            snapshot?.failureContext?.stepArtifacts,
        )
        assertEquals(emptyList<String>(), snapshot?.failureContext?.runArtifacts)
    }

    @Test
    fun shouldFormatPolicyDisabledScreenshotArtifactForFailureContext() = runBlocking {
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionWithDiagnostics, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(newerRun)),
                stepRunsByRunId = mapOf(
                    "run-newer" to listOf(
                        stepRun1,
                        stepRun2.copy(
                            artifactsJson = "{\"artifactType\":\"screenshot_skipped\",\"reason\":\"DIAG_SCREENSHOT_CAPTURE_DISABLED_BY_POLICY\",\"captureRequested\":false,\"sensitiveContextActive\":false}",
                        ),
                    ),
                ),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-newer", recentRunLimit = 5)

        assertEquals(
            listOf(
                "step-2=截图已跳过 | 原因=诊断策略已关闭截图采集 (DIAG_SCREENSHOT_CAPTURE_DISABLED_BY_POLICY) | 请求截图=false | 敏感上下文=false",
            ),
            snapshot?.failureContext?.stepArtifacts,
        )
        assertEquals(emptyList<String>(), snapshot?.failureContext?.runArtifacts)
    }

    @Test
    fun shouldPreserveRawPayloadForKnownArtifactWhenAdditionalFieldsExist() = runBlocking {
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionWithDiagnostics, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(newerRun)),
                stepRunsByRunId = mapOf(
                    "run-newer" to listOf(
                        stepRun1,
                        stepRun2.copy(
                            artifactsJson = "{\"artifactType\":\"screenshot_unavailable\",\"reason\":\"DIAG_SCREENSHOT_CAPTURE_NOT_IMPLEMENTED\",\"captureRequested\":true,\"sensitiveContextActive\":false,\"collector\":\"runner-v2\"}",
                        ),
                    ),
                ),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-newer", recentRunLimit = 5)

        assertEquals(
            listOf(
                "step-2=截图不可用 | 原因=当前版本未实现截图采集 (DIAG_SCREENSHOT_CAPTURE_NOT_IMPLEMENTED) | 请求截图=true | 敏感上下文=false | raw={\"artifactType\":\"screenshot_unavailable\",\"reason\":\"DIAG_SCREENSHOT_CAPTURE_NOT_IMPLEMENTED\",\"captureRequested\":true,\"sensitiveContextActive\":false,\"collector\":\"runner-v2\"}",
            ),
            snapshot?.failureContext?.stepArtifacts,
        )
        assertEquals(emptyList<String>(), snapshot?.failureContext?.runArtifacts)
    }

    @Test
    fun shouldDeriveRunLevelPolicyDisabledArtifactWhenRunFailsBeforeAnyStep() = runBlocking {
        val runLevelFailure = newerRun.copy(
            runId = "run-level-failure",
            errorCode = "RUNNER_PREPARE_FAILED",
            message = "failed before any step started",
        )
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionWithDiagnostics, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(runLevelFailure)),
                stepRunsByRunId = mapOf("run-level-failure" to emptyList()),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-level-failure", recentRunLimit = 5)

        assertEquals(
            listOf("run=截图已跳过 | 原因=诊断策略已关闭失败截图采集"),
            snapshot?.failureContext?.runArtifacts,
        )
        assertEquals(emptyList<String>(), snapshot?.failureContext?.stepArtifacts)
    }

    @Test
    fun shouldDeriveRunLevelUnavailableArtifactWhenRunFailsBeforeAnyStepAndCaptureEnabled() = runBlocking {
        val runLevelFailure = newerRun.copy(
            runId = "run-level-unavailable",
            errorCode = "RUNNER_PREPARE_FAILED",
            message = "failed before any step started",
        )
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionRecord, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(runLevelFailure)),
                stepRunsByRunId = mapOf("run-level-unavailable" to emptyList()),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-level-unavailable", recentRunLimit = 5)

        assertEquals(
            listOf("run=截图不可用 | 原因=当前版本未实现失败截图采集"),
            snapshot?.failureContext?.runArtifacts,
        )
        assertEquals(emptyList<String>(), snapshot?.failureContext?.stepArtifacts)
    }

    @Test
    fun shouldDescribePreActionBlockedRunWithoutStepRecords() = runBlocking {
        val blockedRun = newerRun.copy(
            runId = "run-blocked",
            status = "blocked",
            errorCode = "SCHEDULER_PRECHECK_BLOCKED",
            message = "precheck blocked before first action",
        )
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionRecord, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(blockedRun)),
                stepRunsByRunId = mapOf("run-blocked" to emptyList()),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-blocked", recentRunLimit = 5)

        assertEquals(
            listOf("run=截图未采集 | 原因=阻断发生在首个动作步骤前"),
            snapshot?.failureContext?.runArtifacts,
        )
        assertEquals(emptyList<String>(), snapshot?.failureContext?.stepArtifacts)
        assertEquals(0, snapshot?.failureContext?.failedStepCount)
    }

    @Test
    fun shouldPreferPersistedRunArtifactWhenAvailable() = runBlocking {
        val runWithArtifact = newerRun.copy(
            runId = "run-persisted-artifact",
            status = "timed_out",
            errorCode = "STEP_TIMEOUT",
            message = "task timed out before step record",
            artifactsJson = "{\"artifactType\":\"screenshot_skipped\",\"reason\":\"DIAG_SCREENSHOT_CAPTURE_DISABLED_BY_POLICY\",\"captureRequested\":false,\"sensitiveContextActive\":false}",
        )
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionWithDiagnostics, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(runWithArtifact)),
                stepRunsByRunId = mapOf("run-persisted-artifact" to emptyList()),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-persisted-artifact", recentRunLimit = 5)

        assertEquals(
            listOf("run=截图已跳过 | 原因=诊断策略已关闭截图采集 (DIAG_SCREENSHOT_CAPTURE_DISABLED_BY_POLICY) | 请求截图=false | 敏感上下文=false"),
            snapshot?.failureContext?.runArtifacts,
        )
    }

    @Test
    fun shouldFormatPreExecutionTimeoutRunArtifactReason() = runBlocking {
        val runWithArtifact = newerRun.copy(
            runId = "run-pre-execution-timeout",
            status = "timed_out",
            errorCode = "SCHEDULER_MAX_DURATION_REACHED",
            message = "session maxDuration reached before execution",
            artifactsJson = "{\"artifactType\":\"screenshot_skipped\",\"reason\":\"DIAG_SCREENSHOT_NOT_CAPTURED_BEFORE_EXECUTION_TIMEOUT\",\"captureRequested\":false,\"sensitiveContextActive\":false}",
        )
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionRecord, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(runWithArtifact)),
                stepRunsByRunId = mapOf("run-pre-execution-timeout" to emptyList()),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-pre-execution-timeout", recentRunLimit = 5)

        assertEquals(
            listOf("run=截图已跳过 | 原因=执行前已达到会话超时条件，未采集截图 (DIAG_SCREENSHOT_NOT_CAPTURED_BEFORE_EXECUTION_TIMEOUT) | 请求截图=false | 敏感上下文=false"),
            snapshot?.failureContext?.runArtifacts,
        )
    }

    @Test
    fun shouldFormatExecutionExceptionRunArtifactReason() = runBlocking {
        val runWithArtifact = newerRun.copy(
            runId = "run-execution-exception",
            status = "blocked",
            errorCode = "SCHEDULER_EXECUTION_EXCEPTION",
            message = "runner failure for continuous-task-a",
            artifactsJson = "{\"artifactType\":\"screenshot_unavailable\",\"reason\":\"DIAG_SCREENSHOT_UNAVAILABLE_FOR_EXECUTION_EXCEPTION\",\"captureRequested\":true,\"sensitiveContextActive\":false}",
        )
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionRecord, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(runWithArtifact)),
                stepRunsByRunId = mapOf("run-execution-exception" to emptyList()),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-execution-exception", recentRunLimit = 5)

        assertEquals(
            listOf("run=截图不可用 | 原因=执行异常导致截图不可用 (DIAG_SCREENSHOT_UNAVAILABLE_FOR_EXECUTION_EXCEPTION) | 请求截图=true | 敏感上下文=false"),
            snapshot?.failureContext?.runArtifacts,
        )
    }

    @Test
    fun shouldFormatStorageLimitReachedRunArtifactReason() = runBlocking {
        val runWithArtifact = newerRun.copy(
            runId = "run-storage-limit",
            status = "timed_out",
            errorCode = "STEP_TIMEOUT",
            message = "task timed out before screenshot capture",
            artifactsJson = "{\"artifactType\":\"screenshot_skipped\",\"reason\":\"DIAG_ARTIFACT_STORAGE_LIMIT_REACHED\",\"captureRequested\":true,\"sensitiveContextActive\":false}",
        )
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionRecord, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(runWithArtifact)),
                stepRunsByRunId = mapOf("run-storage-limit" to emptyList()),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-storage-limit", recentRunLimit = 5)

        assertEquals(
            listOf("run=截图已跳过 | 原因=诊断产物存储预算已达上限，跳过截图采集 (DIAG_ARTIFACT_STORAGE_LIMIT_REACHED) | 请求截图=true | 敏感上下文=false"),
            snapshot?.failureContext?.runArtifacts,
        )
    }

    @Test
    fun shouldNotTreatBlockedRunWithRecordedStepsAsPreActionBlock() = runBlocking {
        val blockedRun = newerRun.copy(
            runId = "run-blocked-with-steps",
            status = "blocked",
            errorCode = "RUNNER_BLOCKED",
            message = "blocked after entering flow",
        )
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionWithDiagnostics, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(blockedRun)),
                stepRunsByRunId = mapOf(
                    "run-blocked-with-steps" to listOf(
                        stepRun1,
                        stepRun2.copy(
                            artifactsJson = "{\"artifactType\":\"screenshot_skipped\",\"reason\":\"DIAG_SCREENSHOT_CAPTURE_DISABLED_BY_POLICY\",\"captureRequested\":false,\"sensitiveContextActive\":false}",
                        ),
                    ),
                ),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-blocked-with-steps", recentRunLimit = 5)

        assertEquals(emptyList<String>(), snapshot?.failureContext?.runArtifacts)
        assertEquals(
            listOf(
                "step-2=截图已跳过 | 原因=诊断策略已关闭截图采集 (DIAG_SCREENSHOT_CAPTURE_DISABLED_BY_POLICY) | 请求截图=false | 敏感上下文=false",
            ),
            snapshot?.failureContext?.stepArtifacts,
        )
    }

    @Test
    fun shouldUseExplicitSelectedHistoricalRun() = runBlocking {
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionRecord, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(newerRun, olderRun)),
                stepRunsByRunId = mapOf(
                    "run-older" to listOf(
                        StepRunRecord(
                            runId = "run-older",
                            stepId = "step-old",
                            status = "failed",
                            startedAt = 1_001L,
                            finishedAt = 1_050L,
                            durationMs = 49L,
                            errorCode = "RUNNER_STEP_FAILED",
                            message = "tap failed",
                            artifactsJson = "{}",
                        ),
                    ),
                ),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-older", recentRunLimit = 5)

        assertEquals("run-older", snapshot?.selectedRun?.runId)
        assertNull(snapshot?.failureContext)
        assertEquals(listOf("step-old"), snapshot?.stepRuns?.map { it.stepId })
    }

    @Test
    fun shouldIgnoreRecoveredFailedAttemptsWhenRunSucceeds() = runBlocking {
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionRecord, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(olderRun)),
                stepRunsByRunId = mapOf(
                    "run-older" to listOf(
                        StepRunRecord(
                            runId = "run-older",
                            stepId = "step-retry",
                            status = "failed",
                            startedAt = 1_001L,
                            finishedAt = 1_020L,
                            durationMs = 19L,
                            errorCode = "RUNNER_STEP_FAILED",
                            message = "first attempt failed",
                            artifactsJson = "{}",
                        ),
                        StepRunRecord(
                            runId = "run-older",
                            stepId = "step-retry",
                            status = "success",
                            startedAt = 1_021L,
                            finishedAt = 1_040L,
                            durationMs = 19L,
                            errorCode = null,
                            message = null,
                            artifactsJson = "{}",
                        ),
                    ),
                ),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-older", recentRunLimit = 5)

        assertEquals("run-older", snapshot?.selectedRun?.runId)
        assertNull(snapshot?.failureContext)
    }

    @Test
    fun shouldUseLatestFailedAttemptPerStepForFailureContext() = runBlocking {
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionRecord, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(newerRun)),
                stepRunsByRunId = mapOf(
                    "run-newer" to listOf(
                        StepRunRecord(
                            runId = "run-newer",
                            stepId = "step-retry",
                            status = "failed",
                            startedAt = 2_001L,
                            finishedAt = 2_010L,
                            durationMs = 9L,
                            errorCode = "RUNNER_STEP_FAILED",
                            message = "retry first failed",
                            artifactsJson = "{}",
                        ),
                        StepRunRecord(
                            runId = "run-newer",
                            stepId = "step-retry",
                            status = "success",
                            startedAt = 2_011L,
                            finishedAt = 2_020L,
                            durationMs = 9L,
                            errorCode = null,
                            message = null,
                            artifactsJson = "{}",
                        ),
                        StepRunRecord(
                            runId = "run-newer",
                            stepId = "step-terminal",
                            status = "failed",
                            startedAt = 2_021L,
                            finishedAt = 2_040L,
                            durationMs = 19L,
                            errorCode = "RUNNER_STEP_FAILED",
                            message = "terminal failed",
                            artifactsJson = "{\"artifactType\":\"log\"}",
                        ),
                    ),
                ),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-newer", recentRunLimit = 5)

        assertEquals("step-terminal", snapshot?.failureContext?.primaryFailedStepId)
        assertEquals(1, snapshot?.failureContext?.failedStepCount)
        assertEquals(listOf("step-terminal={\"artifactType\":\"log\"}"), snapshot?.failureContext?.stepArtifacts)
    }

    @Test
    fun shouldReturnNoRecentRunsWhenRecentRunLimitIsNegative() = runBlocking {
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionRecord, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf("task-a" to listOf(newerRun, olderRun)),
                stepRunsByRunId = mapOf("run-newer" to listOf(stepRun1, stepRun2)),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = null, recentRunLimit = -5)

        assertNotNull(snapshot)
        assertEquals(emptyList<RecentRunSummary>(), snapshot?.recentRuns)
        assertNull(snapshot?.selectedRun)
        assertEquals(emptyList<StepRunRecord>(), snapshot?.stepRuns)
    }

    @Test
    fun shouldFallbackToLatestRunWhenSelectedRunBelongsToAnotherTask() = runBlocking {
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionRecord, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(
                recentRunsByTaskId = mapOf(
                    "task-a" to listOf(newerRun, olderRun),
                    "task-b" to listOf(
                        TaskRunRecord(
                            runId = "run-other-task",
                            sessionId = null,
                            cycleNo = null,
                            taskId = "task-b",
                            credentialSetId = null,
                            credentialProfileId = null,
                            credentialAlias = null,
                            status = "failed",
                            startedAt = 3_000L,
                            finishedAt = 3_050L,
                            durationMs = 50L,
                            triggerType = "manual",
                            errorCode = "RUNNER_STEP_FAILED",
                            message = null,
                        ),
                    ),
                ),
                stepRunsByRunId = mapOf(
                    "run-newer" to listOf(stepRun1, stepRun2),
                    "run-other-task" to listOf(stepRunOtherTask),
                ),
            ),
            sessionRepository = FakeSessionRepository(runningSession),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = "run-other-task", recentRunLimit = 5)

        assertEquals(listOf("run-newer", "run-older"), snapshot?.recentRuns?.map { it.runId })
        assertEquals("run-newer", snapshot?.selectedRun?.runId)
        assertEquals(listOf("step-1", "step-2"), snapshot?.stepRuns?.map { it.stepId })
    }

    @Test
    fun shouldReturnTaskMetadataWhenTaskHasNoRuns() = runBlocking {
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(taskDefinitionRecord, taskScheduleStateRecord),
            runRecordRepository = FakeRunRecordRepository(),
            sessionRepository = FakeSessionRepository(null),
        )

        val snapshot = service.loadTaskDetail(taskId = "task-a", selectedRunId = null, recentRunLimit = 5)

        assertNotNull(snapshot)
        assertEquals("task-a", snapshot?.definition?.taskId)
        assertEquals(emptyList<RecentRunSummary>(), snapshot?.recentRuns)
        assertNull(snapshot?.selectedRun)
        assertEquals(emptyList<StepRunRecord>(), snapshot?.stepRuns)
    }

    @Test
    fun shouldReturnNullWhenTaskDoesNotExist() = runBlocking {
        val service = TaskMonitoringDetailService(
            taskRepository = FakeTaskRepository(definition = null, scheduleState = null),
            runRecordRepository = FakeRunRecordRepository(),
            sessionRepository = FakeSessionRepository(null),
        )

        val snapshot = service.loadTaskDetail(taskId = "missing-task", selectedRunId = null, recentRunLimit = 5)

        assertNull(snapshot)
    }

    private class FakeTaskRepository(
        private val definition: TaskDefinitionRecord?,
        private val scheduleState: TaskScheduleStateRecord?,
    ) : TaskRepository {
        override suspend fun listTaskDefinitions(): List<TaskDefinitionRecord> = listOfNotNull(definition)

        override suspend fun getTaskDefinition(taskId: String): TaskDefinitionRecord? =
            definition?.takeIf { it.taskId == taskId }

        override suspend fun upsertTaskDefinition(taskDefinition: TaskDefinitionRecord) = Unit

        override suspend fun updateDefinitionStatus(taskId: String, definitionStatus: String, updatedAt: Long) = Unit

        override suspend fun updateEnabled(taskId: String, enabled: Boolean, updatedAt: Long) = Unit

        override suspend fun getScheduleState(taskId: String): TaskScheduleStateRecord? =
            scheduleState?.takeIf { it.taskId == taskId }

        override suspend fun upsertScheduleState(taskScheduleState: TaskScheduleStateRecord) = Unit
    }

    private class FakeRunRecordRepository(
        private val recentRunsByTaskId: Map<String, List<TaskRunRecord>> = emptyMap(),
        private val stepRunsByRunId: Map<String, List<StepRunRecord>> = emptyMap(),
    ) : RunRecordRepository {
        override suspend fun upsertTaskRun(taskRun: TaskRunRecord) = Unit

        override suspend fun findLatestTaskRun(taskId: String): TaskRunRecord? =
            recentRunsByTaskId[taskId]?.firstOrNull()

        override suspend fun listRecentTaskRuns(limit: Int): List<TaskRunRecord> =
            recentRunsByTaskId.values.flatten()
                .sortedWith(compareByDescending<TaskRunRecord> { it.startedAt }.thenByDescending { it.runId })
                .take(limit.coerceAtLeast(0))

        override suspend fun listRecentTaskRunsByTaskId(taskId: String, limit: Int): List<TaskRunRecord> =
            recentRunsByTaskId[taskId].orEmpty().take(limit.coerceAtLeast(0))

        override suspend fun findTaskRunsBySession(sessionId: String): List<TaskRunRecord> =
            recentRunsByTaskId.values.flatten().filter { it.sessionId == sessionId }

        override suspend fun insertStepRuns(stepRuns: List<StepRunRecord>) = Unit

        override suspend fun findStepRuns(runId: String): List<StepRunRecord> = stepRunsByRunId[runId].orEmpty()
    }

    private class FakeSessionRepository(
        private val runningSession: ContinuousSessionRecord?,
    ) : SessionRepository {
        override suspend fun upsertSession(session: ContinuousSessionRecord) = Unit

        override suspend fun findRunningSession(taskId: String): ContinuousSessionRecord? =
            runningSession?.takeIf { it.taskId == taskId }

        override suspend fun findRunningSessions(): List<ContinuousSessionRecord> = listOfNotNull(runningSession)

        override suspend fun updateTerminalState(
            sessionId: String,
            status: String,
            finishedAt: Long?,
            totalCycles: Int,
            successCycles: Int,
            failedCycles: Int,
            lastErrorCode: String?,
        ) = Unit
    }

    private companion object {
                val validTaskRawJson = """
                        {
                            "schemaVersion": "1.0",
                            "taskId": "task-a",
                            "name": "任务 A",
                            "enabled": true,
                            "targetApp": { "packageName": "com.example.target" },
                            "trigger": { "type": "continuous", "cooldownMs": 1000 },
                            "accountRotation": { "credentialSetId": "set-a" },
                            "executionPolicy": {
                                "taskTimeoutMs": 60000,
                                "maxRetries": 0,
                                "retryBackoffMs": 1000,
                                "conflictPolicy": "skip",
                                "onMissedSchedule": "skip"
                            },
                            "steps": [
                                {
                                    "id": "step-1",
                                    "type": "start_app",
                                    "timeoutMs": 1000,
                                    "params": { "packageName": "com.example.target" }
                                }
                            ]
                        }
                """.trimIndent()

                val validTaskRawJsonWithDiagnostics = """
                        {
                            "schemaVersion": "1.0",
                            "taskId": "task-a",
                            "name": "任务 A",
                            "enabled": true,
                            "targetApp": { "packageName": "com.example.target" },
                            "trigger": { "type": "continuous", "cooldownMs": 1000 },
                            "accountRotation": { "credentialSetId": "set-a" },
                            "diagnostics": {
                                "captureScreenshotOnFailure": false,
                                "captureScreenshotOnStepFailure": false,
                                "logLevel": "debug"
                            },
                            "executionPolicy": {
                                "taskTimeoutMs": 60000,
                                "maxRetries": 0,
                                "retryBackoffMs": 1000,
                                "conflictPolicy": "skip",
                                "onMissedSchedule": "skip"
                            },
                            "steps": [
                                {
                                    "id": "step-1",
                                    "type": "start_app",
                                    "timeoutMs": 1000,
                                    "params": { "packageName": "com.example.target" }
                                }
                            ]
                        }
                """.trimIndent()

        val taskDefinitionRecord = TaskDefinitionRecord(
            taskId = "task-a",
            name = "任务 A",
            enabled = true,
            triggerType = "continuous",
            definitionStatus = "ready",
            rawJson = validTaskRawJson,
            updatedAt = 100L,
        )

        val taskDefinitionWithDiagnostics = taskDefinitionRecord.copy(
            rawJson = validTaskRawJsonWithDiagnostics,
        )

        val taskScheduleStateRecord = TaskScheduleStateRecord(
            taskId = "task-a",
            nextTriggerAt = 5_000L,
            standbyEnabled = true,
            lastTriggerAt = 4_000L,
            lastScheduleStatus = "scheduled",
        )

        val runningSession = ContinuousSessionRecord(
            sessionId = "session-a",
            taskId = "task-a",
            credentialSetId = "set-a",
            status = "running",
            startedAt = 1_500L,
            finishedAt = null,
            totalCycles = 3,
            successCycles = 2,
            failedCycles = 1,
            currentCredentialProfileId = "profile-a",
            currentCredentialAlias = "账号A",
            nextCredentialProfileId = "profile-b",
            nextCredentialAlias = "账号B",
            cursorIndex = 1,
            lastErrorCode = "RUNNER_STEP_FAILED",
        )

        val newerRun = TaskRunRecord(
            runId = "run-newer",
            sessionId = "session-a",
            cycleNo = 3,
            taskId = "task-a",
            credentialSetId = "set-a",
            credentialProfileId = "profile-a",
            credentialAlias = "账号A",
            status = "failed",
            startedAt = 2_000L,
            finishedAt = 2_060L,
            durationMs = 60L,
            triggerType = "continuous",
            errorCode = "RUNNER_STEP_FAILED",
            message = "run newer failed",
        )

        val olderRun = TaskRunRecord(
            runId = "run-older",
            sessionId = "session-a",
            cycleNo = 2,
            taskId = "task-a",
            credentialSetId = "set-a",
            credentialProfileId = "profile-a",
            credentialAlias = "账号A",
            status = "success",
            startedAt = 1_000L,
            finishedAt = 1_050L,
            durationMs = 50L,
            triggerType = "continuous",
            errorCode = null,
            message = null,
        )

        val stepRun1 = StepRunRecord(
            runId = "run-newer",
            stepId = "step-1",
            status = "success",
            startedAt = 2_001L,
            finishedAt = 2_010L,
            durationMs = 9L,
            errorCode = null,
            message = null,
            artifactsJson = "{}",
        )

        val stepRun2 = StepRunRecord(
            runId = "run-newer",
            stepId = "step-2",
            status = "failed",
            startedAt = 2_011L,
            finishedAt = 2_030L,
            durationMs = 19L,
            errorCode = "RUNNER_STEP_FAILED",
            message = "tap failed",
            artifactsJson = "{}",
        )

        val stepRunOtherTask = StepRunRecord(
            runId = "run-other-task",
            stepId = "step-other",
            status = "failed",
            startedAt = 3_001L,
            finishedAt = 3_030L,
            durationMs = 29L,
            errorCode = "RUNNER_STEP_FAILED",
            message = "other task failed",
            artifactsJson = "{}",
        )
    }
}
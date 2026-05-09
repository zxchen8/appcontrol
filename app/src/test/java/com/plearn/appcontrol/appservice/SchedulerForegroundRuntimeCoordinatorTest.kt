package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskScheduleStateRecord
import com.plearn.appcontrol.data.repository.TaskRepository
import com.plearn.appcontrol.scheduler.SchedulerDispatchMode
import com.plearn.appcontrol.scheduler.SchedulerDispatchResult
import com.plearn.appcontrol.scheduler.SchedulerTimeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SchedulerForegroundRuntimeCoordinatorTest {
    @Test
    fun shouldRecoverWhenActiveSchedulesExist() = runBlocking {
        val taskRepository = FakeTaskRepository(
            definitions = listOf(
                TaskDefinitionRecord(
                    taskId = "cron-task",
                    name = "Cron Task",
                    enabled = true,
                    triggerType = "cron",
                    definitionStatus = "ready",
                    rawJson = "{}",
                    updatedAt = 1L,
                ),
            ),
        )
        val alarmScheduler = RecordingAlarmScheduler()
        val dispatcher = RecordingRecoveryDispatcher()
        val coordinator = SchedulerForegroundRuntimeCoordinator(
            taskRepository = taskRepository,
            recoveryOrchestrator = SchedulerRecoveryOrchestrator(dispatcher::dispatch),
            recoveryAlarmScheduler = alarmScheduler,
            schedulerTimeSource = FixedSchedulerTimeSource(nowMs = 1_000L),
        )

        val result = coordinator.start(SchedulerRecoveryTrigger.BOOT_COMPLETED)

        assertTrue(result.keepRunning)
        assertEquals(1, result.activeScheduleCount)
        assertEquals(SchedulerDispatchMode.RECOVERY, dispatcher.lastMode)
        assertEquals(1, alarmScheduler.cancelCalls)
        assertEquals(0, alarmScheduler.scheduleCalls)
    }

    @Test
    fun shouldStopWithoutRecoveringWhenNoActiveSchedulesExist() = runBlocking {
        val taskRepository = FakeTaskRepository(definitions = emptyList())
        val alarmScheduler = RecordingAlarmScheduler()
        val dispatcher = RecordingRecoveryDispatcher()
        val coordinator = SchedulerForegroundRuntimeCoordinator(
            taskRepository = taskRepository,
            recoveryOrchestrator = SchedulerRecoveryOrchestrator(dispatcher::dispatch),
            recoveryAlarmScheduler = alarmScheduler,
            schedulerTimeSource = FixedSchedulerTimeSource(nowMs = 1_000L),
        )

        val result = coordinator.start(SchedulerRecoveryTrigger.PROCESS_RESTART)

        assertFalse(result.keepRunning)
        assertEquals(0, result.activeScheduleCount)
        assertEquals(1, alarmScheduler.cancelCalls)
        assertEquals(null, dispatcher.lastMode)
    }

    @Test
    fun shouldDispatchNormalModeWhenStandbyTickRunsWithActiveSchedules() = runBlocking {
        val taskRepository = FakeTaskRepository(
            definitions = listOf(
                TaskDefinitionRecord(
                    taskId = "cron-task",
                    name = "Cron Task",
                    enabled = true,
                    triggerType = "cron",
                    definitionStatus = "ready",
                    rawJson = "{}",
                    updatedAt = 1L,
                ),
            ),
        )
        val alarmScheduler = RecordingAlarmScheduler()
        val dispatcher = RecordingRecoveryDispatcher()
        val coordinator = SchedulerForegroundRuntimeCoordinator(
            taskRepository = taskRepository,
            recoveryOrchestrator = SchedulerRecoveryOrchestrator(dispatcher::dispatch),
            recoveryAlarmScheduler = alarmScheduler,
            schedulerTimeSource = FixedSchedulerTimeSource(nowMs = 1_000L),
        )

        val result = coordinator.dispatchStandby()

        assertTrue(result.keepRunning)
        assertEquals(1, result.activeScheduleCount)
        assertEquals(SchedulerDispatchMode.NORMAL, dispatcher.lastMode)
        assertEquals(1, alarmScheduler.cancelCalls)
        assertEquals(0, alarmScheduler.scheduleCalls)
    }

    @Test
    fun shouldScheduleAlarmWhenServiceStopsUnexpectedlyWithActiveStandby() {
        val alarmScheduler = RecordingAlarmScheduler()
        val coordinator = SchedulerForegroundRuntimeCoordinator(
            taskRepository = FakeTaskRepository(definitions = emptyList()),
            recoveryOrchestrator = SchedulerRecoveryOrchestrator(RecordingRecoveryDispatcher()::dispatch),
            recoveryAlarmScheduler = alarmScheduler,
            schedulerTimeSource = FixedSchedulerTimeSource(nowMs = 1_000L),
        )

        coordinator.onServiceStopped(stopRequested = false, standbyActive = true)

        assertEquals(1, alarmScheduler.scheduleCalls)
        assertEquals(0, alarmScheduler.cancelCalls)
    }

    @Test
    fun shouldCancelAlarmWhenServiceStopsByRequest() {
        val alarmScheduler = RecordingAlarmScheduler()
        val coordinator = SchedulerForegroundRuntimeCoordinator(
            taskRepository = FakeTaskRepository(definitions = emptyList()),
            recoveryOrchestrator = SchedulerRecoveryOrchestrator(RecordingRecoveryDispatcher()::dispatch),
            recoveryAlarmScheduler = alarmScheduler,
            schedulerTimeSource = FixedSchedulerTimeSource(nowMs = 1_000L),
        )

        coordinator.onServiceStopped(stopRequested = true, standbyActive = true)

        assertEquals(0, alarmScheduler.scheduleCalls)
        assertEquals(1, alarmScheduler.cancelCalls)
    }

    @Test
    fun shouldReturnDelayUntilEarliestStandbyTrigger() = runBlocking {
        val coordinator = SchedulerForegroundRuntimeCoordinator(
            taskRepository = FakeTaskRepository(
                definitions = listOf(
                    TaskDefinitionRecord(
                        taskId = "cron-task",
                        name = "Cron Task",
                        enabled = true,
                        triggerType = "cron",
                        definitionStatus = "ready",
                        rawJson = "{}",
                        updatedAt = 1L,
                    ),
                    TaskDefinitionRecord(
                        taskId = "continuous-task",
                        name = "Continuous Task",
                        enabled = true,
                        triggerType = "continuous",
                        definitionStatus = "ready",
                        rawJson = "{}",
                        updatedAt = 1L,
                    ),
                ),
                scheduleStates = mapOf(
                    "cron-task" to TaskScheduleStateRecord(
                        taskId = "cron-task",
                        nextTriggerAt = 5_000L,
                        standbyEnabled = true,
                        lastTriggerAt = 1_000L,
                        lastScheduleStatus = "scheduled",
                    ),
                    "continuous-task" to TaskScheduleStateRecord(
                        taskId = "continuous-task",
                        nextTriggerAt = 3_500L,
                        standbyEnabled = true,
                        lastTriggerAt = 1_000L,
                        lastScheduleStatus = "scheduled",
                    ),
                ),
            ),
            recoveryOrchestrator = SchedulerRecoveryOrchestrator(RecordingRecoveryDispatcher()::dispatch),
            recoveryAlarmScheduler = RecordingAlarmScheduler(),
            schedulerTimeSource = FixedSchedulerTimeSource(nowMs = 2_000L),
        )

        val delayMs = coordinator.nextStandbyDelayMs()

        assertEquals(1_500L, delayMs)
    }

    private class FakeTaskRepository(
        private val definitions: List<TaskDefinitionRecord>,
        private val scheduleStates: Map<String, TaskScheduleStateRecord> = emptyMap(),
    ) : TaskRepository {
        override suspend fun listTaskDefinitions(): List<TaskDefinitionRecord> = definitions

        override suspend fun getTaskDefinition(taskId: String): TaskDefinitionRecord? = definitions.firstOrNull { it.taskId == taskId }

        override suspend fun upsertTaskDefinition(taskDefinition: TaskDefinitionRecord) = Unit

        override suspend fun updateDefinitionStatus(taskId: String, definitionStatus: String, updatedAt: Long) = Unit

        override suspend fun updateEnabled(taskId: String, enabled: Boolean, updatedAt: Long) = Unit

        override suspend fun getScheduleState(taskId: String): TaskScheduleStateRecord? = scheduleStates[taskId]

        override suspend fun upsertScheduleState(taskScheduleState: TaskScheduleStateRecord) = Unit
    }

    private class RecordingRecoveryDispatcher {
        var lastMode: SchedulerDispatchMode? = null

        suspend fun dispatch(mode: SchedulerDispatchMode): SchedulerDispatchResult {
            lastMode = mode
            return SchedulerDispatchResult(executedTaskIds = emptyList())
        }
    }

    private class RecordingAlarmScheduler : SchedulerRecoveryAlarmScheduler {
        var scheduleCalls: Int = 0
        var cancelCalls: Int = 0

        override fun scheduleProcessRestartRecovery(delayMs: Long) {
            scheduleCalls += 1
        }

        override fun cancelRecovery() {
            cancelCalls += 1
        }
    }

    private class FixedSchedulerTimeSource(
        private val nowMs: Long,
    ) : SchedulerTimeSource {
        override fun nowMs(): Long = nowMs

        override suspend fun delay(durationMs: Long) = Unit
    }
}
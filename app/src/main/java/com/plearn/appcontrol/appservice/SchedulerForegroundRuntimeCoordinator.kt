package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.data.repository.TaskRepository
import com.plearn.appcontrol.scheduler.SchedulerDispatchMode
import com.plearn.appcontrol.scheduler.SchedulerDispatchResult
import com.plearn.appcontrol.scheduler.SchedulerTimeSource
import javax.inject.Inject

data class SchedulerRuntimeStartResult(
    val keepRunning: Boolean,
    val activeScheduleCount: Int,
    val dispatchResult: SchedulerDispatchResult? = null,
)

class SchedulerForegroundRuntimeCoordinator @Inject constructor(
    private val taskRepository: TaskRepository,
    private val recoveryOrchestrator: SchedulerRecoveryOrchestrator,
    private val recoveryAlarmScheduler: SchedulerRecoveryAlarmScheduler,
    private val schedulerTimeSource: SchedulerTimeSource,
) {
    suspend fun start(trigger: SchedulerRecoveryTrigger): SchedulerRuntimeStartResult {
        return when (trigger) {
            SchedulerRecoveryTrigger.PROCESS_RESTART,
            SchedulerRecoveryTrigger.BOOT_COMPLETED,
            SchedulerRecoveryTrigger.PACKAGE_REPLACED,
            -> dispatch(SchedulerDispatchMode.RECOVERY)
        }
    }

    suspend fun dispatchStandby(): SchedulerRuntimeStartResult = dispatch(SchedulerDispatchMode.NORMAL)

    suspend fun nextStandbyDelayMs(): Long? {
        val nextTriggerAt = nextStandbyTriggerAt() ?: return null
        return (nextTriggerAt - schedulerTimeSource.nowMs()).coerceAtLeast(0L)
    }

    private suspend fun dispatch(mode: SchedulerDispatchMode): SchedulerRuntimeStartResult {
        val activeScheduleCount = activeScheduleCount()
        if (activeScheduleCount == 0) {
            recoveryAlarmScheduler.cancelRecovery()
            return SchedulerRuntimeStartResult(
                keepRunning = false,
                activeScheduleCount = 0,
            )
        }

        recoveryAlarmScheduler.cancelRecovery()
        val dispatchResult = recoveryOrchestrator.dispatch(mode)
        return SchedulerRuntimeStartResult(
            keepRunning = true,
            activeScheduleCount = activeScheduleCount,
            dispatchResult = dispatchResult,
        )
    }

    fun onServiceStopped(stopRequested: Boolean, standbyActive: Boolean) {
        if (stopRequested || !standbyActive) {
            recoveryAlarmScheduler.cancelRecovery()
            return
        }

        recoveryAlarmScheduler.scheduleProcessRestartRecovery()
    }

    private suspend fun activeScheduleCount(): Int {
        var activeCount = 0
        for (definition in taskRepository.listTaskDefinitions()) {
            if (!definition.enabled || !definition.definitionStatus.equals("ready", ignoreCase = true)) {
                continue
            }
            if (definition.triggerType != "cron" && definition.triggerType != "continuous") {
                continue
            }

            val scheduleState = taskRepository.getScheduleState(definition.taskId)
            if (scheduleState == null || (scheduleState.standbyEnabled && scheduleState.nextTriggerAt != null)) {
                activeCount += 1
            }
        }
        return activeCount
    }

    private suspend fun nextStandbyTriggerAt(): Long? {
        var earliestTriggerAt: Long? = null
        for (definition in taskRepository.listTaskDefinitions()) {
            if (!definition.enabled || !definition.definitionStatus.equals("ready", ignoreCase = true)) {
                continue
            }
            if (definition.triggerType != "cron" && definition.triggerType != "continuous") {
                continue
            }

            val scheduleState = taskRepository.getScheduleState(definition.taskId) ?: continue
            if (!scheduleState.standbyEnabled) {
                continue
            }

            val nextTriggerAt = scheduleState.nextTriggerAt ?: continue
            earliestTriggerAt = when {
                earliestTriggerAt == null -> nextTriggerAt
                nextTriggerAt < earliestTriggerAt -> nextTriggerAt
                else -> earliestTriggerAt
            }
        }

        return earliestTriggerAt
    }
}
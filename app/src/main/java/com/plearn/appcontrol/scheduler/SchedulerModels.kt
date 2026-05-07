package com.plearn.appcontrol.scheduler

interface SchedulerTimeSource {
    fun nowMs(): Long
}

object SystemSchedulerTimeSource : SchedulerTimeSource {
    override fun nowMs(): Long = System.currentTimeMillis()
}

data class SchedulerDispatchResult(
    val executedTaskIds: List<String>,
)

object ScheduleStatus {
    const val IDLE = "idle"
    const val SCHEDULED = "scheduled"
    const val BLOCKED = "blocked"
}

object SchedulerFailureCode {
    const val SCHEDULER_CREDENTIAL_SET_UNAVAILABLE = "SCHEDULER_CREDENTIAL_SET_UNAVAILABLE"
}
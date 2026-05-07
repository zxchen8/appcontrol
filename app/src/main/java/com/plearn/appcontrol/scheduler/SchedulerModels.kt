package com.plearn.appcontrol.scheduler

enum class SchedulerDispatchMode {
    NORMAL,
    RECOVERY,
}

interface TaskExecutionLock {
    suspend fun tryAcquire(packageName: String): Boolean

    suspend fun release(packageName: String)
}

class InMemoryTaskExecutionLock : TaskExecutionLock {
    private val lockedPackages = mutableSetOf<String>()

    override suspend fun tryAcquire(packageName: String): Boolean = synchronized(this) {
        if (lockedPackages.contains(packageName)) {
            false
        } else {
            lockedPackages += packageName
            true
        }
    }

    override suspend fun release(packageName: String) {
        synchronized(this) {
            lockedPackages.remove(packageName)
        }
    }
}

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
    const val CONFLICT_SKIPPED = "conflict_skipped"
    const val CONFLICT_DELAYED = "conflict_delayed"
    const val MISSED_SKIPPED = "missed_skipped"
    const val BLOCKED = "blocked"
}

object SchedulerFailureCode {
    const val SCHEDULER_CREDENTIAL_SET_UNAVAILABLE = "SCHEDULER_CREDENTIAL_SET_UNAVAILABLE"
    const val SCHED_CONFLICT_SKIPPED = "SCHED_CONFLICT_SKIPPED"
    const val SCHED_CONFLICT_DELAYED = "SCHED_CONFLICT_DELAYED"
    const val SCHED_MISSED_SKIPPED = "SCHED_MISSED_SKIPPED"
}
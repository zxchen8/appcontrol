package com.plearn.appcontrol.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.plearn.appcontrol.data.local.AppControlDatabase
import com.plearn.appcontrol.data.local.dao.DiagnosticsEventDao
import com.plearn.appcontrol.data.local.dao.StepRunDao
import com.plearn.appcontrol.data.local.dao.TaskRunDao
import com.plearn.appcontrol.data.local.dao.TaskRunKey
import com.plearn.appcontrol.data.model.DiagnosticsEventRecord
import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import com.plearn.appcontrol.diagnostics.CAPTURE_GATE_DENIED
import com.plearn.appcontrol.diagnostics.CLEANUP
import com.plearn.appcontrol.diagnostics.AndroidDiagnosticsRetentionLogger
import com.plearn.appcontrol.diagnostics.DiagnosticsCaptureGateLogEvent
import com.plearn.appcontrol.diagnostics.DiagnosticsCleanupLogEvent
import com.plearn.appcontrol.diagnostics.DiagnosticsRetentionLogger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.TimeUnit

data class DiagnosticsRetentionPolicy(
    val maxRunsPerTask: Int = DEFAULT_MAX_RUNS_PER_TASK,
    val maxEventsPerTask: Int = DEFAULT_MAX_EVENTS_PER_TASK,
    val maxGlobalEvents: Int = DEFAULT_MAX_GLOBAL_EVENTS,
    val maxScreenshotArtifactsPerTask: Int = DEFAULT_MAX_SCREENSHOT_ARTIFACTS_PER_TASK,
    val maxAgeMs: Long = DEFAULT_MAX_AGE_MS,
    val maxStorageBytes: Long = DEFAULT_MAX_STORAGE_BYTES,
    val captureHighWatermarkBytes: Long = DEFAULT_CAPTURE_HIGH_WATERMARK_BYTES,
) {
    init {
        require(maxRunsPerTask >= 0) { "maxRunsPerTask must be >= 0" }
        require(maxEventsPerTask >= 0) { "maxEventsPerTask must be >= 0" }
        require(maxGlobalEvents >= 0) { "maxGlobalEvents must be >= 0" }
        require(maxScreenshotArtifactsPerTask >= 0) { "maxScreenshotArtifactsPerTask must be >= 0" }
        require(maxAgeMs >= 0) { "maxAgeMs must be >= 0" }
        require(maxStorageBytes >= 0) { "maxStorageBytes must be >= 0" }
        require(captureHighWatermarkBytes >= 0) { "captureHighWatermarkBytes must be >= 0" }
        require(captureHighWatermarkBytes <= maxStorageBytes) {
            "captureHighWatermarkBytes must be <= maxStorageBytes"
        }
    }

    companion object {
        const val DEFAULT_MAX_RUNS_PER_TASK: Int = 500
        private const val DEFAULT_EVENT_TYPES_PER_RUN: Int = 2
        const val DEFAULT_MAX_EVENTS_PER_TASK: Int = DEFAULT_MAX_RUNS_PER_TASK * DEFAULT_EVENT_TYPES_PER_RUN
        const val DEFAULT_MAX_GLOBAL_EVENTS: Int = DEFAULT_MAX_RUNS_PER_TASK
        const val DEFAULT_MAX_SCREENSHOT_ARTIFACTS_PER_TASK: Int = DEFAULT_MAX_RUNS_PER_TASK
        val DEFAULT_MAX_AGE_MS: Long = TimeUnit.DAYS.toMillis(14)
        const val DEFAULT_MAX_STORAGE_BYTES: Long = 512L * 1024L * 1024L
        const val DEFAULT_CAPTURE_HIGH_WATERMARK_BYTES: Long = DEFAULT_MAX_STORAGE_BYTES * 9L / 10L
    }
}

data class DiagnosticsArtifactRunRef(
    val taskId: String,
    val runId: String,
)

interface DiagnosticsArtifactFileStore {
    fun estimateStorageBytes(): Long

    fun prune(
        maxAgeMs: Long,
        maxArtifactsPerTask: Int,
        activeRunRefsByTask: Map<String, Set<String>>,
        protectedRunRef: DiagnosticsArtifactRunRef? = null,
    )

    fun deleteOldestArtifact(protectedRunRef: DiagnosticsArtifactRunRef? = null): Boolean
}

object NoOpDiagnosticsArtifactFileStore : DiagnosticsArtifactFileStore {
    override fun estimateStorageBytes(): Long = 0L

    override fun prune(
        maxAgeMs: Long,
        maxArtifactsPerTask: Int,
        activeRunRefsByTask: Map<String, Set<String>>,
        protectedRunRef: DiagnosticsArtifactRunRef?,
    ) = Unit

    override fun deleteOldestArtifact(protectedRunRef: DiagnosticsArtifactRunRef?): Boolean = false
}

class FileBackedDiagnosticsArtifactFileStore(
    private val screenshotRootDir: File,
    private val nowMsProvider: () -> Long = System::currentTimeMillis,
) : DiagnosticsArtifactFileStore {
    override fun estimateStorageBytes(): Long = listArtifacts().sumOf(StoredArtifact::fileSizeBytes)

    override fun prune(
        maxAgeMs: Long,
        maxArtifactsPerTask: Int,
        activeRunRefsByTask: Map<String, Set<String>>,
        protectedRunRef: DiagnosticsArtifactRunRef?,
    ) {
        val sanitizedActiveRunRefsByTask = sanitizeActiveRunRefsByTask(activeRunRefsByTask)
        val sanitizedProtectedRunRef = protectedRunRef?.sanitize()
        val cutoffModifiedAt = (nowMsProvider() - maxAgeMs).coerceAtLeast(0L)

        listArtifacts().forEach { artifact ->
            val isProtected = artifact.matches(sanitizedProtectedRunRef)
            val isActive = sanitizedActiveRunRefsByTask[artifact.taskId]?.contains(artifact.runId) == true
            if (!isProtected && (!isActive || artifact.lastModifiedAt < cutoffModifiedAt)) {
                deleteArtifact(artifact)
            }
        }

        listArtifacts()
            .groupBy(StoredArtifact::taskId)
            .values
            .forEach { artifactsForTask ->
                var retainedCount = 0
                for (artifact in artifactsForTask.sortedWith(compareByDescending<StoredArtifact> { it.lastModifiedAt }.thenByDescending { it.relativePath })) {
                    if (artifact.matches(sanitizedProtectedRunRef)) {
                        continue
                    }
                    if (retainedCount < maxArtifactsPerTask) {
                        retainedCount += 1
                        continue
                    }
                    deleteArtifact(artifact)
                }
            }
    }

    override fun deleteOldestArtifact(protectedRunRef: DiagnosticsArtifactRunRef?): Boolean {
        val sanitizedProtectedRunRef = protectedRunRef?.sanitize()
        val oldestArtifact = listArtifacts()
            .filterNot { it.matches(sanitizedProtectedRunRef) }
            .minWithOrNull(compareBy<StoredArtifact> { it.lastModifiedAt }.thenBy { it.relativePath })
            ?: return false
        return deleteArtifact(oldestArtifact)
    }

    private fun listArtifacts(): List<StoredArtifact> {
        if (!screenshotRootDir.exists()) {
            return emptyList()
        }
        return screenshotRootDir.listFiles()
            .orEmpty()
            .filter(File::isDirectory)
            .flatMap { taskDirectory ->
                taskDirectory.listFiles()
                    .orEmpty()
                    .filter(File::isDirectory)
                    .flatMap { runDirectory ->
                        runDirectory.listFiles()
                            .orEmpty()
                            .filter(File::isFile)
                            .map { artifactFile ->
                                StoredArtifact(
                                    file = artifactFile,
                                    taskId = taskDirectory.name,
                                    runId = runDirectory.name,
                                    relativePath = buildString {
                                        append(SCREENSHOT_RELATIVE_ROOT)
                                        append('/')
                                        append(taskDirectory.name)
                                        append('/')
                                        append(runDirectory.name)
                                        append('/')
                                        append(artifactFile.name)
                                    },
                                    fileSizeBytes = artifactFile.length(),
                                    lastModifiedAt = artifactFile.lastModified(),
                                )
                            }
                    }
            }
    }

    private fun sanitizeActiveRunRefsByTask(activeRunRefsByTask: Map<String, Set<String>>): Map<String, Set<String>> =
        activeRunRefsByTask.mapKeys { sanitizePathSegment(it.key) }
            .mapValues { (_, runIds) -> runIds.map(::sanitizePathSegment).toSet() }

    private fun DiagnosticsArtifactRunRef.sanitize(): DiagnosticsArtifactRunRef = DiagnosticsArtifactRunRef(
        taskId = sanitizePathSegment(taskId),
        runId = sanitizePathSegment(runId),
    )

    private fun deleteArtifact(artifact: StoredArtifact): Boolean {
        if (!artifact.file.delete()) {
            return false
        }
        cleanupEmptyParents(artifact.file.parentFile)
        return true
    }

    private fun cleanupEmptyParents(directory: File?) {
        var current = directory
        while (current != null && current != screenshotRootDir) {
            if (!current.isDirectory || !current.list().isNullOrEmpty()) {
                return
            }
            current.delete()
            current = current.parentFile
        }
    }

    private fun sanitizePathSegment(value: String): String = value.replace(PATH_SEGMENT_REGEX, "_").ifBlank { "artifact" }

    private data class StoredArtifact(
        val file: File,
        val taskId: String,
        val runId: String,
        val relativePath: String,
        val fileSizeBytes: Long,
        val lastModifiedAt: Long,
    ) {
        fun matches(runRef: DiagnosticsArtifactRunRef?): Boolean =
            runRef != null && taskId == runRef.taskId && runId == runRef.runId
    }

    private companion object {
        private val PATH_SEGMENT_REGEX = Regex("[^a-zA-Z0-9._-]")
        private const val SCREENSHOT_RELATIVE_ROOT = "diagnostics/screenshots"
    }
}

interface RunRecordRepository {
    suspend fun upsertTaskRun(taskRun: TaskRunRecord)
    suspend fun findLatestTaskRun(taskId: String): TaskRunRecord?
    suspend fun listRecentTaskRuns(limit: Int): List<TaskRunRecord>
    suspend fun listRecentTaskRunsByTaskId(taskId: String, limit: Int): List<TaskRunRecord>
    suspend fun findTaskRunsBySession(sessionId: String): List<TaskRunRecord>
    suspend fun insertStepRuns(stepRuns: List<StepRunRecord>)
    suspend fun findStepRuns(runId: String): List<StepRunRecord>
    suspend fun listRecentDiagnosticsEvents(taskId: String, limit: Int, runId: String? = null): List<DiagnosticsEventRecord> = emptyList()
    suspend fun canCaptureFailureArtifact(taskId: String? = null, runId: String? = null): Boolean = true

    suspend fun recordTaskRun(taskRun: TaskRunRecord, stepRuns: List<StepRunRecord>) {
        upsertTaskRun(taskRun)
        if (stepRuns.isNotEmpty()) {
            insertStepRuns(stepRuns)
        }
    }
}

class RoomRunRecordRepository(
    private val database: AppControlDatabase,
    private val taskRunDao: TaskRunDao,
    private val stepRunDao: StepRunDao,
    private val diagnosticsEventDao: DiagnosticsEventDao = database.diagnosticsEventDao(),
    private val diagnosticsArtifactFileStore: DiagnosticsArtifactFileStore = NoOpDiagnosticsArtifactFileStore,
    private val retentionPolicy: DiagnosticsRetentionPolicy = DiagnosticsRetentionPolicy(),
    private val nowMsProvider: () -> Long = System::currentTimeMillis,
    private val retentionLogger: DiagnosticsRetentionLogger = AndroidDiagnosticsRetentionLogger,
) : RunRecordRepository {
    override suspend fun upsertTaskRun(taskRun: TaskRunRecord) {
        taskRunDao.upsert(taskRun.toEntity())
    }

    override suspend fun findLatestTaskRun(taskId: String): TaskRunRecord? =
        taskRunDao.findLatestByTaskId(taskId)?.toRecord()

    override suspend fun listRecentTaskRuns(limit: Int): List<TaskRunRecord> =
        taskRunDao.listRecent(normalizeLimit(limit)).map { it.toRecord() }

    override suspend fun listRecentTaskRunsByTaskId(taskId: String, limit: Int): List<TaskRunRecord> =
        taskRunDao.listRecentByTaskId(taskId, normalizeLimit(limit)).map { it.toRecord() }

    override suspend fun findTaskRunsBySession(sessionId: String): List<TaskRunRecord> =
        taskRunDao.findBySessionId(sessionId).map { it.toRecord() }

    override suspend fun insertStepRuns(stepRuns: List<StepRunRecord>) {
        stepRunDao.insertAll(stepRuns.map { it.toEntity() })
    }

    override suspend fun findStepRuns(runId: String): List<StepRunRecord> =
        stepRunDao.findByRunId(runId).map { it.toRecord() }

    override suspend fun listRecentDiagnosticsEvents(taskId: String, limit: Int, runId: String?): List<DiagnosticsEventRecord> =
        if (runId == null) {
            diagnosticsEventDao.listRecentForTask(taskId, normalizeLimit(limit)).map { it.toRecord() }
        } else {
            diagnosticsEventDao.listRecentForTaskAndRun(taskId, runId, normalizeLimit(limit)).map { it.toRecord() }
        }

    override suspend fun canCaptureFailureArtifact(taskId: String?, runId: String?): Boolean = database.withTransaction {
        pruneRetainedRunsForStartup(trigger = CleanupTrigger.CAPTURE_GATE, taskId = taskId, runId = runId)
        val usedBytes = currentDiagnosticsStorageBytes()
        val canCapture = usedBytes < retentionPolicy.captureHighWatermarkBytes
        if (!canCapture) {
            val deniedEvent = DiagnosticsCaptureGateLogEvent(
                trigger = CleanupTrigger.CAPTURE_GATE,
                usedBytes = usedBytes,
                captureHighWatermarkBytes = retentionPolicy.captureHighWatermarkBytes,
                maxStorageBytes = retentionPolicy.maxStorageBytes,
            )
            val protectedEventId = insertDiagnosticsEvent(
                taskId = taskId,
                runId = runId,
                eventType = CAPTURE_GATE_DENIED,
                payloadJson = deniedEvent.toPayloadJson(),
            )
            pruneDiagnosticsEventCountBudget(taskId)
            pruneRunsBeyondStorageBudget(
                protectedRunId = runId,
                protectedEventId = protectedEventId,
                protectedArtifactRunRef = protectedArtifactRunRef(taskId, runId),
            )
            logCaptureGateDeniedSafely(
                deniedEvent,
            )
        }
        canCapture
    }

    override suspend fun recordTaskRun(taskRun: TaskRunRecord, stepRuns: List<StepRunRecord>) {
        database.withTransaction {
            taskRunDao.upsert(taskRun.toEntity())
            if (stepRuns.isNotEmpty()) {
                stepRunDao.insertAll(stepRuns.map { it.toEntity() })
            }
            pruneRetainedRuns(taskRun)
        }
    }

    suspend fun pruneRetainedRunsAtStartup() {
        database.withTransaction {
            pruneRetainedRunsForStartup(trigger = CleanupTrigger.STARTUP)
        }
    }

    private fun normalizeLimit(limit: Int): Int = limit.coerceAtLeast(0)

    private suspend fun pruneRetainedRuns(taskRun: TaskRunRecord) {
        logCleanupIfChanged(
            trigger = CleanupTrigger.RECORD_TASK_RUN,
            taskId = taskRun.taskId,
            runId = taskRun.runId,
        ) {
            val cutoffStartedAt = (nowMsProvider() - retentionPolicy.maxAgeMs).coerceAtLeast(0L)
            taskRunDao.deleteStartedBefore(cutoffStartedAt, taskRun.runId)
            diagnosticsEventDao.deleteCreatedBefore(cutoffStartedAt)
            taskRunDao.deleteAllExceptMostRecent(
                taskId = taskRun.taskId,
                retainCount = normalizeLimit(retentionPolicy.maxRunsPerTask),
                protectedRunId = taskRun.runId,
            )
            diagnosticsEventDao.deleteAllExceptMostRecentForTask(taskRun.taskId, normalizeLimit(retentionPolicy.maxEventsPerTask))
            diagnosticsEventDao.deleteAllExceptMostRecentGlobal(normalizeLimit(retentionPolicy.maxGlobalEvents))
            pruneOrphanedDiagnosticsEvents(protectedRunId = taskRun.runId)
            pruneDiagnosticsArtifactFiles(protectedRunRef = DiagnosticsArtifactRunRef(taskRun.taskId, taskRun.runId))
            pruneRunsBeyondStorageBudget(
                protectedRunId = taskRun.runId,
                protectedArtifactRunRef = DiagnosticsArtifactRunRef(taskRun.taskId, taskRun.runId),
            )
        }
    }

    private suspend fun pruneRetainedRunsForStartup(trigger: String, taskId: String? = null, runId: String? = null) {
        logCleanupIfChanged(trigger = trigger, taskId = taskId, runId = runId) {
            val cutoffStartedAt = (nowMsProvider() - retentionPolicy.maxAgeMs).coerceAtLeast(0L)
            taskRunDao.deleteStartedBefore(cutoffStartedAt)
            diagnosticsEventDao.deleteCreatedBefore(cutoffStartedAt)
            (taskRunDao.listTaskIds() + diagnosticsEventDao.listTaskIds()).distinct().forEach { taskIdToPrune ->
                taskRunDao.deleteAllExceptMostRecent(taskIdToPrune, normalizeLimit(retentionPolicy.maxRunsPerTask))
                diagnosticsEventDao.deleteAllExceptMostRecentForTask(taskIdToPrune, normalizeLimit(retentionPolicy.maxEventsPerTask))
            }
            diagnosticsEventDao.deleteAllExceptMostRecentGlobal(normalizeLimit(retentionPolicy.maxGlobalEvents))
            pruneOrphanedDiagnosticsEvents(protectedRunId = runId)
            pruneDiagnosticsArtifactFiles(protectedRunRef = protectedArtifactRunRef(taskId, runId))
            pruneRunsBeyondStorageBudget(protectedArtifactRunRef = protectedArtifactRunRef(taskId, runId))
        }
    }

    private suspend fun pruneRunsBeyondStorageBudget(
        protectedRunId: String? = null,
        protectedEventId: Long? = null,
        protectedArtifactRunRef: DiagnosticsArtifactRunRef? = null,
    ) {
        while (currentDiagnosticsStorageBytes() > retentionPolicy.maxStorageBytes) {
            val oldestEventId = diagnosticsEventDao.findOldestId(protectedEventId)
            if (oldestEventId != null) {
                diagnosticsEventDao.deleteById(oldestEventId)
                continue
            }
            if (diagnosticsArtifactFileStore.deleteOldestArtifact(protectedArtifactRunRef)) {
                continue
            }
            val oldestRunId = taskRunDao.findOldestRunId(protectedRunId) ?: break
            taskRunDao.deleteByRunId(oldestRunId)
            pruneOrphanedDiagnosticsEvents(protectedRunId = protectedRunId)
            pruneDiagnosticsArtifactFiles(protectedRunRef = protectedArtifactRunRef)
        }
    }

    private suspend fun currentDiagnosticsStorageBytes(): Long =
        taskRunDao.estimateDiagnosticsStorageBytes() +
            stepRunDao.estimateDiagnosticsStorageBytes() +
            diagnosticsEventDao.estimateDiagnosticsStorageBytes() +
            diagnosticsArtifactFileStore.estimateStorageBytes()

    private suspend fun pruneDiagnosticsArtifactFiles(protectedRunRef: DiagnosticsArtifactRunRef? = null) {
        diagnosticsArtifactFileStore.prune(
            maxAgeMs = retentionPolicy.maxAgeMs,
            maxArtifactsPerTask = normalizeLimit(retentionPolicy.maxScreenshotArtifactsPerTask),
            activeRunRefsByTask = taskRunDao.listAllRunKeys()
                .groupBy(TaskRunKey::taskId)
                .mapValues { (_, runKeys) -> runKeys.map(TaskRunKey::runId).toSet() },
            protectedRunRef = protectedRunRef,
        )
    }

    private fun protectedArtifactRunRef(taskId: String?, runId: String?): DiagnosticsArtifactRunRef? =
        if (taskId == null || runId == null) null else DiagnosticsArtifactRunRef(taskId = taskId, runId = runId)

    private suspend fun pruneOrphanedDiagnosticsEvents(protectedRunId: String? = null) {
        diagnosticsEventDao.deleteOrphanedRunScopedEvents(protectedRunId)
        diagnosticsEventDao.deleteOrphanedTaskScopedEvents()
    }

    private suspend fun logCleanupIfChanged(
        trigger: String,
        taskId: String? = null,
        runId: String? = null,
        block: suspend () -> Unit,
    ) {
        val beforeState = snapshotCleanupState()
        block()
        val afterState = snapshotCleanupState()
        val deletedRunCount = (beforeState.runCount - afterState.runCount).coerceAtLeast(0)
        if (deletedRunCount == 0 && afterState.bytes >= beforeState.bytes) {
            return
        }
        val cleanupEvent = DiagnosticsCleanupLogEvent(
            trigger = trigger,
            deletedRunCount = deletedRunCount,
            beforeBytes = beforeState.bytes,
            afterBytes = afterState.bytes,
            maxStorageBytes = retentionPolicy.maxStorageBytes,
            captureHighWatermarkBytes = retentionPolicy.captureHighWatermarkBytes,
        )
        val protectedEventId = insertDiagnosticsEvent(
            taskId = taskId,
            runId = runId,
            eventType = CLEANUP,
            payloadJson = cleanupEvent.toPayloadJson(),
        )
        pruneDiagnosticsEventCountBudget(taskId)
        pruneRunsBeyondStorageBudget(
            protectedRunId = runId,
            protectedEventId = protectedEventId,
            protectedArtifactRunRef = protectedArtifactRunRef(taskId, runId),
        )
        logCleanupSafely(cleanupEvent)
    }

    private suspend fun insertDiagnosticsEvent(
        taskId: String?,
        runId: String?,
        eventType: String,
        payloadJson: String,
    ): Long {
        if (runId != null && eventType == CAPTURE_GATE_DENIED) {
            diagnosticsEventDao.deleteRunScopedEvent(taskId = taskId, runId = runId, eventType = eventType)
        }
        return diagnosticsEventDao.insert(
            DiagnosticsEventRecord(
                taskId = taskId,
                runId = runId,
                createdAt = nowMsProvider(),
                eventType = eventType,
                payloadJson = payloadJson,
            ).toEntity(),
        )
    }

    private suspend fun pruneDiagnosticsEventCountBudget(taskId: String?) {
        if (taskId == null) {
            diagnosticsEventDao.deleteAllExceptMostRecentGlobal(normalizeLimit(retentionPolicy.maxGlobalEvents))
            return
        }
        diagnosticsEventDao.deleteAllExceptMostRecentForTask(taskId, normalizeLimit(retentionPolicy.maxEventsPerTask))
    }

    private suspend fun snapshotCleanupState(): CleanupState = CleanupState(
        runCount = taskRunDao.countAll(),
        bytes = currentDiagnosticsStorageBytes(),
    )

    private data class CleanupState(
        val runCount: Int,
        val bytes: Long,
    )

    private fun logCleanupSafely(event: DiagnosticsCleanupLogEvent) {
        try {
            retentionLogger.logCleanup(event)
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to emit diagnostics cleanup log.", error)
        }
    }

    private fun logCaptureGateDeniedSafely(event: DiagnosticsCaptureGateLogEvent) {
        try {
            retentionLogger.logCaptureGateDenied(event)
        } catch (error: Throwable) {
            Log.w(TAG, "Failed to emit diagnostics capture gate denial log.", error)
        }
    }

    private object CleanupTrigger {
        const val STARTUP = "startup"
        const val RECORD_TASK_RUN = "record_task_run"
        const val CAPTURE_GATE = "capture_gate"
    }

    private companion object {
        const val TAG = "RunRecordRepo"
    }
}

private fun DiagnosticsCleanupLogEvent.toPayloadJson(): String = buildJsonObject {
    put("trigger", trigger)
    put("deletedRunCount", deletedRunCount)
    put("beforeBytes", beforeBytes)
    put("afterBytes", afterBytes)
    put("maxStorageBytes", maxStorageBytes)
    put("captureHighWatermarkBytes", captureHighWatermarkBytes)
}.toString()

private fun DiagnosticsCaptureGateLogEvent.toPayloadJson(): String = buildJsonObject {
    put("trigger", trigger)
    put("usedBytes", usedBytes)
    put("captureHighWatermarkBytes", captureHighWatermarkBytes)
    put("maxStorageBytes", maxStorageBytes)
}.toString()
package com.plearn.appcontrol.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.plearn.appcontrol.data.local.AppControlDatabase
import com.plearn.appcontrol.data.model.ContinuousSessionRecord
import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ContinuousSessionPersistenceRepositoryTest {
    private lateinit var database: AppControlDatabase
    private lateinit var repository: ContinuousSessionPersistenceRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppControlDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomContinuousSessionPersistenceRepository(
            database,
            database.taskRunDao(),
            database.stepRunDao(),
            database.continuousSessionDao(),
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    // -- helpers --

    private fun seedTaskAndCredentialSet(taskId: String = TASK_ID, credentialSetId: String = CREDENTIAL_SET_ID) = runBlocking {
        val taskRepo = RoomTaskRepository(database.taskDefinitionDao(), database.taskScheduleStateDao())
        taskRepo.upsertTaskDefinition(
            com.plearn.appcontrol.data.model.TaskDefinitionRecord(
                taskId = taskId,
                name = "Test Task",
                enabled = true,
                triggerType = "continuous",
                definitionStatus = "ready",
                rawJson = "{}",
                updatedAt = 1L,
            ),
        )
        val credRepo = RoomCredentialRepository(database.credentialProfileDao(), database.credentialSetDao())
        credRepo.upsertCredentialProfile(
            com.plearn.appcontrol.data.model.CredentialProfileRecord(
                profileId = "profile-a",
                alias = "Profile A",
                tagsJson = "[]",
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        credRepo.replaceCredentialSet(
            com.plearn.appcontrol.data.model.CredentialSetRecord(
                credentialSetId = credentialSetId,
                name = "Test Set",
                description = "test",
                strategy = "round_robin",
                enabled = true,
                createdAt = 1L,
                updatedAt = 1L,
                items = listOf(
                    com.plearn.appcontrol.data.model.CredentialSetItemRecord(credentialSetId, "profile-a", 0, true),
                ),
            ),
        )
    }

    private fun seedSession(session: ContinuousSessionRecord) = runBlocking {
        database.continuousSessionDao().upsert(session.toEntity())
    }

    private fun makeSession(
        sessionId: String = SESSION_ID,
        taskId: String = TASK_ID,
        totalCycles: Int = 2,
        successCycles: Int = 1,
        failedCycles: Int = 1,
        cursorIndex: Int = 3,
        nextCredentialProfileId: String? = "profile-a",
        nextCredentialAlias: String? = "Profile A",
        lastErrorCode: String? = null,
    ) = ContinuousSessionRecord(
        sessionId = sessionId,
        taskId = taskId,
        credentialSetId = CREDENTIAL_SET_ID,
        status = "running",
        startedAt = 1000L,
        finishedAt = null,
        totalCycles = totalCycles,
        successCycles = successCycles,
        failedCycles = failedCycles,
        currentCredentialProfileId = "profile-a",
        currentCredentialAlias = "Profile A",
        nextCredentialProfileId = nextCredentialProfileId,
        nextCredentialAlias = nextCredentialAlias,
        cursorIndex = cursorIndex,
        lastErrorCode = lastErrorCode,
    )

    private fun makeTaskRun(
        runId: String = "run-1",
        sessionId: String = SESSION_ID,
        cycleNo: Int = 3,
        status: String = "success",
        errorCode: String? = null,
        message: String? = null,
    ) = TaskRunRecord(
        runId = runId,
        sessionId = sessionId,
        cycleNo = cycleNo,
        taskId = TASK_ID,
        credentialSetId = CREDENTIAL_SET_ID,
        credentialProfileId = "profile-a",
        credentialAlias = "Profile A",
        status = status,
        startedAt = 2000L,
        finishedAt = 3000L,
        durationMs = 1000L,
        triggerType = "continuous",
        errorCode = errorCode,
        message = message,
    )

    private fun makeStepRuns(runId: String = "run-1") = listOf(
        StepRunRecord(
            runId = runId,
            stepId = "step-1",
            status = "success",
            startedAt = 2000L,
            finishedAt = 2500L,
            durationMs = 500L,
            errorCode = null,
            message = null,
            artifactsJson = "{}",
        ),
        StepRunRecord(
            runId = runId,
            stepId = "step-2",
            status = "success",
            startedAt = 2500L,
            finishedAt = 3000L,
            durationMs = 500L,
            errorCode = null,
            message = null,
            artifactsJson = "{}",
        ),
    )

    // -- tests --

    @Test
    fun recordCycleProgress_persistsRunAndSessionUpdateTogether() = runBlocking {
        seedTaskAndCredentialSet()
        val initialSession = makeSession(totalCycles = 2, successCycles = 1, failedCycles = 1, cursorIndex = 3)
        seedSession(initialSession)

        val taskRun = makeTaskRun(runId = "run-3", cycleNo = 3)
        val stepRuns = makeStepRuns(runId = "run-3")
        val updatedSession = initialSession.copy(
            totalCycles = 3,
            successCycles = 2,
            cursorIndex = 4,
            nextCredentialProfileId = "next-profile",
            nextCredentialAlias = "Next Profile",
        )

        repository.recordCycleProgress(
            taskRun = taskRun,
            stepRuns = stepRuns,
            session = updatedSession,
        )

        // Verify task run persisted
        val persistedRun = database.taskRunDao().findLatestByTaskId(TASK_ID)
        assertNotNull(persistedRun)
        assertEquals("run-3", persistedRun!!.runId)
        assertEquals(3, persistedRun.cycleNo)

        // Verify step runs persisted
        val persistedSteps = database.stepRunDao().findByRunId("run-3")
        assertEquals(2, persistedSteps.size)

        // Verify session updated atomically
        val persistedSession = database.continuousSessionDao().findRunningSessionByTaskId(TASK_ID)
        assertNotNull(persistedSession)
        assertEquals(3, persistedSession!!.totalCycles)
        assertEquals(2, persistedSession.successCycles)
        assertEquals("next-profile", persistedSession.nextCredentialProfileId)
        assertEquals(4, persistedSession.cursorIndex)
    }

    @Test
    fun recordTerminalCycle_persistsRunAndTerminalSessionUpdateTogether() = runBlocking {
        seedTaskAndCredentialSet()
        val initialSession = makeSession(totalCycles = 4, successCycles = 3, failedCycles = 1)
        seedSession(initialSession)

        val taskRun = makeTaskRun(
            runId = "run-terminal",
            cycleNo = 5,
            status = "failed",
            errorCode = "SCHEDULER_EXECUTION_EXCEPTION",
            message = "Target app crashed",
        )
        val stepRuns = makeStepRuns(runId = "run-terminal")

        repository.recordTerminalCycle(
            taskRun = taskRun,
            stepRuns = stepRuns,
            terminalUpdate = TerminalSessionUpdate(
                sessionId = SESSION_ID,
                status = "blocked",
                finishedAt = 5000L,
                totalCycles = 5,
                successCycles = 3,
                failedCycles = 2,
                lastErrorCode = "SCHEDULER_EXECUTION_EXCEPTION",
            ),
        )

        // Verify task run persisted
        val persistedRuns = database.taskRunDao().findBySessionId(SESSION_ID)
        assertEquals(1, persistedRuns.size)
        assertEquals("run-terminal", persistedRuns.single().runId)

        // Verify step runs persisted
        val persistedSteps = database.stepRunDao().findByRunId("run-terminal")
        assertEquals(2, persistedSteps.size)

        // Verify session terminated atomically — row must exist with terminal values
        val runningSession = database.continuousSessionDao().findRunningSessionByTaskId(TASK_ID)
        assertEquals(null, runningSession)
        val persistedSession = database.continuousSessionDao().findBySessionId(SESSION_ID)
        assertNotNull(persistedSession)
        assertEquals("blocked", persistedSession!!.status)
        assertEquals(5000L, persistedSession.finishedAt)
        assertEquals(5, persistedSession.totalCycles)
        assertEquals(3, persistedSession.successCycles)
        assertEquals(2, persistedSession.failedCycles)
        assertEquals("SCHEDULER_EXECUTION_EXCEPTION", persistedSession.lastErrorCode)
    }

    @Test
    fun recordPreRunBlockedCycle_persistsSyntheticRunAndBlockedSessionTogether() = runBlocking {
        seedTaskAndCredentialSet()
        val initialSession = makeSession(totalCycles = 2, successCycles = 1, failedCycles = 1)
        seedSession(initialSession)

        val syntheticRun = makeTaskRun(
            runId = "run-blocked",
            cycleNo = 3,
            status = "blocked",
            errorCode = "CREDENTIAL_SET_UNAVAILABLE",
            message = "No enabled profiles in credential set",
        )

        repository.recordTerminalCycle(
            taskRun = syntheticRun,
            stepRuns = emptyList(),
            terminalUpdate = TerminalSessionUpdate(
                sessionId = SESSION_ID,
                status = "blocked",
                finishedAt = 4000L,
                totalCycles = 3,
                successCycles = 1,
                failedCycles = 2,
                lastErrorCode = "CREDENTIAL_SET_UNAVAILABLE",
            ),
        )

        // Verify synthetic run persisted
        val persistedRun = database.taskRunDao().findLatestByTaskId(TASK_ID)
        assertNotNull(persistedRun)
        assertEquals("run-blocked", persistedRun!!.runId)
        assertEquals("blocked", persistedRun.status)

        // Verify session terminated atomically — row must exist with terminal values
        val runningSession = database.continuousSessionDao().findRunningSessionByTaskId(TASK_ID)
        assertEquals(null, runningSession)
        val persistedSession = database.continuousSessionDao().findBySessionId(SESSION_ID)
        assertNotNull(persistedSession)
        assertEquals("blocked", persistedSession!!.status)
        assertEquals(4000L, persistedSession.finishedAt)
        assertEquals(3, persistedSession.totalCycles)
        assertEquals(1, persistedSession.successCycles)
        assertEquals(2, persistedSession.failedCycles)
        assertEquals("CREDENTIAL_SET_UNAVAILABLE", persistedSession.lastErrorCode)
    }

    @Test
    fun recordTerminalCycle_rollsBackRunWhenSessionIdNotFound() = runBlocking {
        seedTaskAndCredentialSet()
        // Seed a real session so we can verify it stays unchanged
        val initialSession = makeSession(totalCycles = 2, successCycles = 1, failedCycles = 1)
        seedSession(initialSession)

        val taskRun = makeTaskRun(runId = "run-orphan", cycleNo = 3)
        val stepRuns = makeStepRuns(runId = "run-orphan")

        // Use a non-existent sessionId — the session UPDATE will affect 0 rows
        try {
            repository.recordTerminalCycle(
                taskRun = taskRun,
                stepRuns = stepRuns,
                terminalUpdate = TerminalSessionUpdate(
                    sessionId = "non-existent-session",
                    status = "blocked",
                    finishedAt = 9000L,
                    totalCycles = 99,
                    successCycles = 50,
                    failedCycles = 49,
                    lastErrorCode = "PHANTOM",
                ),
            )
            fail("Expected IllegalStateException for missing session")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("non-existent-session"))
        }

        // Verify task run was NOT persisted (transaction rolled back)
        val orphanRun = database.taskRunDao().findLatestByTaskId(TASK_ID)
        assertNull(orphanRun)

        // Verify step runs were NOT persisted
        val orphanSteps = database.stepRunDao().findByRunId("run-orphan")
        assertEquals(0, orphanSteps.size)

        // Verify original session is completely unchanged
        val unchangedSession = database.continuousSessionDao().findBySessionId(SESSION_ID)
        assertNotNull(unchangedSession)
        assertEquals("running", unchangedSession!!.status)
        assertEquals(2, unchangedSession.totalCycles)
        assertEquals(1, unchangedSession.successCycles)
        assertEquals(1, unchangedSession.failedCycles)
        assertNull(unchangedSession.finishedAt)
        assertNull(unchangedSession.lastErrorCode)
    }

    companion object {
        private const val TASK_ID = "task-persistence-test"
        private const val SESSION_ID = "session-persistence-test"
        private const val CREDENTIAL_SET_ID = "cred-set-test"
    }
}

package com.plearn.appcontrol.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.plearn.appcontrol.data.local.AppControlDatabase
import com.plearn.appcontrol.data.model.CredentialProfileRecord
import com.plearn.appcontrol.data.model.CredentialSetItemRecord
import com.plearn.appcontrol.data.model.CredentialSetRecord
import com.plearn.appcontrol.data.model.StepRunRecord
import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskScheduleStateRecord
import com.plearn.appcontrol.data.model.TaskRunRecord
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomRepositoriesTest {
    private lateinit var database: AppControlDatabase
    private lateinit var taskRepository: TaskRepository
    private lateinit var credentialRepository: CredentialRepository
    private lateinit var runRecordRepository: RunRecordRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppControlDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        taskRepository = RoomTaskRepository(database.taskDefinitionDao(), database.taskScheduleStateDao())
        credentialRepository = RoomCredentialRepository(database.credentialProfileDao(), database.credentialSetDao())
        runRecordRepository = RoomRunRecordRepository(database.taskRunDao(), database.stepRunDao())
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun taskRepositoryShouldPreserveRawJsonAndScheduleState() = runBlocking {
        taskRepository.upsertTaskDefinition(
            TaskDefinitionRecord(
                taskId = "task-a",
                name = "任务A",
                enabled = true,
                triggerType = "cron",
                definitionStatus = "ready",
                rawJson = "{\"taskId\":\"task-a\"}",
                updatedAt = 100,
            ),
        )
        taskRepository.upsertScheduleState(
            TaskScheduleStateRecord(
                taskId = "task-a",
                nextTriggerAt = 200,
                standbyEnabled = true,
                lastTriggerAt = 150,
                lastScheduleStatus = "scheduled",
            ),
        )

        val definition = taskRepository.getTaskDefinition("task-a")
        val scheduleState = taskRepository.getScheduleState("task-a")

        assertNotNull(definition)
        assertEquals("{\"taskId\":\"task-a\"}", definition?.rawJson)
        assertEquals(200L, scheduleState?.nextTriggerAt)
    }

    @Test
    fun credentialRepositoryShouldReturnOrderedCredentialSetWithoutEntities() = runBlocking {
        credentialRepository.upsertCredentialProfile(
            CredentialProfileRecord(
                profileId = "profile-a",
                alias = "账号A",
                tagsJson = "[]",
                enabled = true,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        credentialRepository.upsertCredentialProfile(
            CredentialProfileRecord(
                profileId = "profile-b",
                alias = "账号B",
                tagsJson = "[]",
                enabled = true,
                createdAt = 1,
                updatedAt = 1,
            ),
        )

        credentialRepository.replaceCredentialSet(
            CredentialSetRecord(
                credentialSetId = "smoke-set-a",
                name = "Smoke Set A",
                description = "test accounts",
                strategy = "round_robin",
                enabled = true,
                createdAt = 1,
                updatedAt = 2,
                items = listOf(
                    CredentialSetItemRecord("smoke-set-a", "profile-b", 1, true),
                    CredentialSetItemRecord("smoke-set-a", "profile-a", 0, true),
                ),
            ),
        )

        val credentialSet = credentialRepository.getCredentialSet("smoke-set-a")

        assertNotNull(credentialSet)
        assertEquals(listOf("profile-a", "profile-b"), credentialSet?.items?.map { it.profileId })
    }

    @Test
    fun runRecordRepositoryShouldPersistTaskRunAndStepRuns() = runBlocking {
        taskRepository.upsertTaskDefinition(
            TaskDefinitionRecord(
                taskId = "task-run-a",
                name = "任务运行A",
                enabled = true,
                triggerType = "cron",
                definitionStatus = "ready",
                rawJson = "{\"taskId\":\"task-run-a\"}",
                updatedAt = 100,
            ),
        )

        runRecordRepository.upsertTaskRun(
            TaskRunRecord(
                runId = "run-a",
                sessionId = null,
                cycleNo = null,
                taskId = "task-run-a",
                credentialSetId = null,
                credentialProfileId = null,
                credentialAlias = null,
                status = "success",
                startedAt = 200,
                finishedAt = 260,
                durationMs = 60,
                triggerType = "manual",
                errorCode = null,
                message = null,
            ),
        )
        runRecordRepository.insertStepRuns(
            listOf(
                StepRunRecord(
                    runId = "run-a",
                    stepId = "step-1",
                    status = "success",
                    startedAt = 210,
                    finishedAt = 220,
                    durationMs = 10,
                    errorCode = null,
                    message = null,
                    artifactsJson = "{}",
                ),
                StepRunRecord(
                    runId = "run-a",
                    stepId = "step-2",
                    status = "failed",
                    startedAt = 221,
                    finishedAt = 240,
                    durationMs = 19,
                    errorCode = "STEP_EXECUTION_FAILED",
                    message = "tap failed",
                    artifactsJson = "{}",
                ),
            ),
        )

        val latestTaskRun = runRecordRepository.findLatestTaskRun("task-run-a")
        val storedStepRuns = runRecordRepository.findStepRuns("run-a")

        assertNotNull(latestTaskRun)
        assertEquals("success", latestTaskRun?.status)
        assertEquals(listOf("step-1", "step-2"), storedStepRuns.map { it.stepId })
        assertEquals(listOf("success", "failed"), storedStepRuns.map { it.status })
    }
}
package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.data.model.CredentialProfileRecord
import com.plearn.appcontrol.data.model.CredentialSecretRecord
import com.plearn.appcontrol.data.model.CredentialSetItemRecord
import com.plearn.appcontrol.data.model.CredentialSetRecord
import com.plearn.appcontrol.data.model.TaskDefinitionRecord
import com.plearn.appcontrol.data.model.TaskScheduleStateRecord
import com.plearn.appcontrol.data.repository.CredentialRepository
import com.plearn.appcontrol.data.repository.TaskRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialManagementServiceTest {
    @Test
    fun shouldLoadCredentialSnapshotWithProfilesSetsAndReferencingTasks() = runBlocking {
        val service = CredentialManagementService(
            credentialRepository = FakeCredentialRepository(
                profiles = mutableListOf(profileA, profileB, disabledProfile),
                credentialSets = mutableListOf(setA),
            ),
            taskRepository = FakeTaskRepository(
                definitions = listOf(
                    referencingTask,
                    nonContinuousTask,
                    invalidJsonTask,
                ),
            ),
        )

        val snapshot = service.loadSnapshot()

        assertEquals(listOf("profile-a", "profile-b", "profile-disabled"), snapshot.profiles.map { it.profileId })
        assertEquals(listOf("set-a"), snapshot.credentialSets.map { it.credentialSetId })
        assertEquals(listOf("任务 A"), snapshot.credentialSets.first().referencingTasks.map { it.taskName })
        assertEquals(listOf("task-a"), snapshot.credentialSets.first().referencingTasks.map { it.taskId })
        assertEquals(listOf("账号A", "账号B"), snapshot.credentialSets.first().items.map { it.alias })
    }

    @Test
    fun shouldSaveProfileAndSecretAndExposeProfileInSnapshot() = runBlocking {
        val credentialRepository = FakeCredentialRepository()
        val service = CredentialManagementService(
            credentialRepository = credentialRepository,
            taskRepository = FakeTaskRepository(),
        )

        val result = service.saveProfile(
            profileId = "profile-new",
            alias = "新账号",
            tagsJson = "[\"smoke\"]",
            enabled = true,
            encryptedPayload = "cipher-text",
            encryptionVersion = 2,
        )

        assertTrue(result.saved)
        assertEquals("profile-new", credentialRepository.profiles.single().profileId)
        assertEquals("cipher-text", credentialRepository.secrets.single().encryptedPayload)
        val snapshot = service.loadSnapshot()
        assertEquals(listOf("profile-new"), snapshot.profiles.map { it.profileId })
    }

    @Test
    fun shouldSaveCredentialSetByReplacingItemsAndKeepingStableOrder() = runBlocking {
        val credentialRepository = FakeCredentialRepository(
            profiles = mutableListOf(profileA, profileB, disabledProfile),
            credentialSets = mutableListOf(
                setA.copy(
                    items = listOf(
                        CredentialSetItemRecord(
                            credentialSetId = "set-a",
                            profileId = "profile-disabled",
                            orderNo = 0,
                            enabled = true,
                        ),
                    ),
                ),
            ),
        )
        val service = CredentialManagementService(
            credentialRepository = credentialRepository,
            taskRepository = FakeTaskRepository(),
        )

        val result = service.saveCredentialSet(
            credentialSetId = "set-a",
            name = "轮换组 A",
            description = "updated",
            strategy = "round_robin",
            enabled = true,
            membersInOrder = listOf(
                CredentialSetMemberInput(profileId = "profile-b", enabled = true),
                CredentialSetMemberInput(profileId = "profile-a", enabled = true),
            ),
        )

        assertTrue(result.saved)
        val storedSet = credentialRepository.credentialSets.single()
        assertEquals(listOf("profile-b", "profile-a"), storedSet.items.map { it.profileId })
        assertEquals(listOf(0, 1), storedSet.items.map { it.orderNo })
        val snapshot = service.loadSnapshot()
        assertEquals(listOf("账号B", "账号A"), snapshot.credentialSets.first().items.map { it.alias })
    }

    @Test
    fun shouldPreserveCredentialSetMemberEnabledFlagsWhenSaving() = runBlocking {
        val credentialRepository = FakeCredentialRepository(
            profiles = mutableListOf(profileA, profileB),
            credentialSets = mutableListOf(setA),
        )
        val service = CredentialManagementService(
            credentialRepository = credentialRepository,
            taskRepository = FakeTaskRepository(),
        )

        val result = service.saveCredentialSet(
            credentialSetId = "set-a",
            name = "轮换组 A",
            description = "updated",
            strategy = "round_robin",
            enabled = true,
            membersInOrder = listOf(
                CredentialSetMemberInput(profileId = "profile-a", enabled = false),
                CredentialSetMemberInput(profileId = "profile-b", enabled = true),
            ),
        )

        assertTrue(result.saved)
        val storedSet = credentialRepository.credentialSets.single()
        assertEquals(listOf(false, true), storedSet.items.map { it.enabled })
        val snapshot = service.loadSnapshot()
        assertEquals(listOf(false, true), snapshot.credentialSets.single().items.map { it.itemEnabled })
    }

    @Test
    fun shouldToggleProfileEnabledWithoutDroppingSetMembership() = runBlocking {
        val credentialRepository = FakeCredentialRepository(
            profiles = mutableListOf(profileA, profileB),
            credentialSets = mutableListOf(setA),
        )
        val service = CredentialManagementService(
            credentialRepository = credentialRepository,
            taskRepository = FakeTaskRepository(),
        )

        val result = service.setProfileEnabled(profileId = "profile-a", enabled = false)

        assertTrue(result.updated)
        val snapshot = service.loadSnapshot()
        assertFalse(snapshot.profiles.first { it.profileId == "profile-a" }.enabled)
        assertEquals(listOf("profile-a", "profile-b"), snapshot.credentialSets.first().items.map { it.profileId })
    }

    @Test
    fun shouldToggleCredentialSetEnabledAndKeepTaskReferencesVisible() = runBlocking {
        val credentialRepository = FakeCredentialRepository(
            profiles = mutableListOf(profileA, profileB),
            credentialSets = mutableListOf(setA),
        )
        val service = CredentialManagementService(
            credentialRepository = credentialRepository,
            taskRepository = FakeTaskRepository(definitions = listOf(referencingTask)),
        )

        val result = service.setCredentialSetEnabled(credentialSetId = "set-a", enabled = false)

        assertTrue(result.updated)
        val snapshot = service.loadSnapshot()
        val updatedSet = snapshot.credentialSets.single()
        assertFalse(updatedSet.enabled)
        assertEquals(listOf("task-a"), updatedSet.referencingTasks.map { it.taskId })
    }

    private class FakeCredentialRepository(
        val profiles: MutableList<CredentialProfileRecord> = mutableListOf(),
        val secrets: MutableList<CredentialSecretRecord> = mutableListOf(),
        val credentialSets: MutableList<CredentialSetRecord> = mutableListOf(),
    ) : CredentialRepository {
        override suspend fun upsertCredentialProfile(profile: CredentialProfileRecord) {
            profiles.removeAll { it.profileId == profile.profileId }
            profiles += profile
        }

        override suspend fun upsertCredentialSecret(secret: CredentialSecretRecord) {
            secrets.removeAll { it.profileId == secret.profileId }
            secrets += secret
        }

        override suspend fun getCredentialProfile(profileId: String): CredentialProfileRecord? =
            profiles.firstOrNull { it.profileId == profileId }

        override suspend fun listCredentialProfiles(): List<CredentialProfileRecord> = profiles.sortedBy { it.alias }

        override suspend fun getEnabledProfiles(): List<CredentialProfileRecord> = profiles.filter { it.enabled }.sortedBy { it.alias }

        override suspend fun replaceCredentialSet(credentialSet: CredentialSetRecord) {
            credentialSets.removeAll { it.credentialSetId == credentialSet.credentialSetId }
            credentialSets += credentialSet
        }

        override suspend fun listCredentialSets(): List<CredentialSetRecord> = credentialSets.sortedBy { it.name }

        override suspend fun getCredentialSet(credentialSetId: String): CredentialSetRecord? =
            credentialSets.firstOrNull { it.credentialSetId == credentialSetId }
    }

    private class FakeTaskRepository(
        private val definitions: List<TaskDefinitionRecord> = emptyList(),
    ) : TaskRepository {
        override suspend fun listTaskDefinitions(): List<TaskDefinitionRecord> = definitions

        override suspend fun getTaskDefinition(taskId: String): TaskDefinitionRecord? = definitions.firstOrNull { it.taskId == taskId }

        override suspend fun upsertTaskDefinition(taskDefinition: TaskDefinitionRecord) = Unit

        override suspend fun updateDefinitionStatus(taskId: String, definitionStatus: String, updatedAt: Long) = Unit

        override suspend fun updateEnabled(taskId: String, enabled: Boolean, updatedAt: Long) = Unit

        override suspend fun getScheduleState(taskId: String): TaskScheduleStateRecord? = null

        override suspend fun upsertScheduleState(taskScheduleState: TaskScheduleStateRecord) = Unit
    }

    private companion object {
        val profileA = CredentialProfileRecord(
            profileId = "profile-a",
            alias = "账号A",
            tagsJson = "[]",
            enabled = true,
            createdAt = 1L,
            updatedAt = 1L,
        )

        val profileB = CredentialProfileRecord(
            profileId = "profile-b",
            alias = "账号B",
            tagsJson = "[]",
            enabled = true,
            createdAt = 1L,
            updatedAt = 1L,
        )

        val disabledProfile = CredentialProfileRecord(
            profileId = "profile-disabled",
            alias = "账号Disabled",
            tagsJson = "[]",
            enabled = false,
            createdAt = 1L,
            updatedAt = 1L,
        )

        val setA = CredentialSetRecord(
            credentialSetId = "set-a",
            name = "轮换组 A",
            description = "smoke",
            strategy = "round_robin",
            enabled = true,
            createdAt = 1L,
            updatedAt = 1L,
            items = listOf(
                CredentialSetItemRecord(
                    credentialSetId = "set-a",
                    profileId = "profile-a",
                    orderNo = 0,
                    enabled = true,
                ),
                CredentialSetItemRecord(
                    credentialSetId = "set-a",
                    profileId = "profile-b",
                    orderNo = 1,
                    enabled = true,
                ),
            ),
        )

        val referencingTask = TaskDefinitionRecord(
            taskId = "task-a",
            name = "任务 A",
            enabled = true,
            triggerType = "continuous",
            definitionStatus = "ready",
            rawJson = """
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
                    { "id": "step-1", "type": "start_app", "timeoutMs": 1000, "params": { "packageName": "com.example.target" } }
                  ]
                }
            """.trimIndent(),
            updatedAt = 1L,
        )

        val nonContinuousTask = TaskDefinitionRecord(
            taskId = "task-b",
            name = "任务 B",
            enabled = true,
            triggerType = "cron",
            definitionStatus = "ready",
            rawJson = """
                {
                  "schemaVersion": "1.0",
                  "taskId": "task-b",
                  "name": "任务 B",
                  "enabled": true,
                  "targetApp": { "packageName": "com.example.target" },
                  "trigger": { "type": "cron", "expression": "*/5 * * * *", "timezone": "Asia/Shanghai" },
                  "accountRotation": { "credentialSetId": "set-a" },
                  "executionPolicy": {
                    "taskTimeoutMs": 60000,
                    "maxRetries": 0,
                    "retryBackoffMs": 1000,
                    "conflictPolicy": "skip",
                    "onMissedSchedule": "skip"
                  },
                  "steps": [
                    { "id": "step-1", "type": "start_app", "timeoutMs": 1000, "params": { "packageName": "com.example.target" } }
                  ]
                }
            """.trimIndent(),
            updatedAt = 1L,
        )

        val invalidJsonTask = TaskDefinitionRecord(
            taskId = "task-c",
            name = "任务 C",
            enabled = true,
            triggerType = "continuous",
            definitionStatus = "invalid",
            rawJson = "{invalid-json}",
            updatedAt = 1L,
        )
    }
}
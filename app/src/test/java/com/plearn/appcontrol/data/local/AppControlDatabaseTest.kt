package com.plearn.appcontrol.data.local

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.plearn.appcontrol.data.local.entity.CredentialProfileEntity
import com.plearn.appcontrol.data.local.entity.CredentialSetEntity
import com.plearn.appcontrol.data.local.entity.CredentialSetItemEntity
import com.plearn.appcontrol.data.local.entity.TaskDefinitionEntity
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
class AppControlDatabaseTest {
    private lateinit var database: AppControlDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppControlDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun taskDefinitionDaoShouldRoundTripStoredDefinition() = runBlocking {
        val entity = TaskDefinitionEntity(
            taskId = "daily-login-check",
            name = "每日登录校验",
            enabled = true,
            triggerType = "cron",
            definitionStatus = "ready",
            rawJson = "{\"schemaVersion\":\"1.0\"}",
            updatedAt = 1_715_000_000_000,
        )

        database.taskDefinitionDao().upsert(entity)

        val stored = database.taskDefinitionDao().getByTaskId("daily-login-check")
        assertNotNull(stored)
        assertEquals("ready", stored?.definitionStatus)
        assertEquals("cron", stored?.triggerType)
    }

    @Test
    fun credentialSetDaoShouldReplaceMembersTransactionally() = runBlocking {
        database.credentialProfileDao().upsertProfile(
            CredentialProfileEntity(
                profileId = "profile-a",
                alias = "账号A",
                tagsJson = "[]",
                enabled = true,
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        database.credentialProfileDao().upsertProfile(
            CredentialProfileEntity(
                profileId = "profile-b",
                alias = "账号B",
                tagsJson = "[]",
                enabled = true,
                createdAt = 1,
                updatedAt = 1,
            ),
        )

        val credentialSet = CredentialSetEntity(
            credentialSetId = "smoke-set-a",
            name = "Smoke Set A",
            description = "test accounts",
            strategy = "round_robin",
            enabled = true,
            createdAt = 1,
            updatedAt = 1,
        )

        database.credentialSetDao().replaceCredentialSet(
            credentialSet = credentialSet,
            items = listOf(
                CredentialSetItemEntity("smoke-set-a", "profile-a", 0, true),
            ),
        )
        database.credentialSetDao().replaceCredentialSet(
            credentialSet = credentialSet.copy(updatedAt = 2),
            items = listOf(
                CredentialSetItemEntity("smoke-set-a", "profile-b", 0, true),
            ),
        )

        val relation = database.credentialSetDao().getCredentialSetWithItems("smoke-set-a")
        assertNotNull(relation)
        assertEquals(1, relation?.items?.size)
        assertEquals("profile-b", relation?.items?.first()?.profileId)
    }
}
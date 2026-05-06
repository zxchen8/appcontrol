package com.plearn.appcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.plearn.appcontrol.data.local.entity.CredentialProfileEntity
import com.plearn.appcontrol.data.local.entity.CredentialSecretEntity

@Dao
interface CredentialProfileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: CredentialProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSecret(secret: CredentialSecretEntity)

    @Query("SELECT * FROM credential_profiles WHERE profileId = :profileId LIMIT 1")
    suspend fun getProfileById(profileId: String): CredentialProfileEntity?

    @Query("SELECT * FROM credential_profiles WHERE enabled = 1 ORDER BY alias ASC")
    suspend fun getEnabledProfiles(): List<CredentialProfileEntity>
}
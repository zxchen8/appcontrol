package com.plearn.appcontrol.data.repository

import com.plearn.appcontrol.data.local.dao.CredentialProfileDao
import com.plearn.appcontrol.data.local.dao.CredentialSetDao
import com.plearn.appcontrol.data.model.CredentialProfileRecord
import com.plearn.appcontrol.data.model.CredentialSecretRecord
import com.plearn.appcontrol.data.model.CredentialSetRecord

interface CredentialRepository {
    suspend fun upsertCredentialProfile(profile: CredentialProfileRecord)
    suspend fun upsertCredentialSecret(secret: CredentialSecretRecord)
    suspend fun getEnabledProfiles(): List<CredentialProfileRecord>
    suspend fun replaceCredentialSet(credentialSet: CredentialSetRecord)
    suspend fun getCredentialSet(credentialSetId: String): CredentialSetRecord?
}

class RoomCredentialRepository(
    private val credentialProfileDao: CredentialProfileDao,
    private val credentialSetDao: CredentialSetDao,
) : CredentialRepository {
    override suspend fun upsertCredentialProfile(profile: CredentialProfileRecord) {
        credentialProfileDao.upsertProfile(profile.toEntity())
    }

    override suspend fun upsertCredentialSecret(secret: CredentialSecretRecord) {
        credentialProfileDao.upsertSecret(secret.toEntity())
    }

    override suspend fun getEnabledProfiles(): List<CredentialProfileRecord> =
        credentialProfileDao.getEnabledProfiles().map { it.toRecord() }

    override suspend fun replaceCredentialSet(credentialSet: CredentialSetRecord) {
        credentialSetDao.replaceCredentialSet(
            credentialSet = credentialSet.toEntity(),
            items = credentialSet.items.map { it.toEntity() },
        )
    }

    override suspend fun getCredentialSet(credentialSetId: String): CredentialSetRecord? =
        credentialSetDao.getCredentialSetWithItems(credentialSetId)?.toRecord()
}
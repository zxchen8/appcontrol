package com.plearn.appcontrol.data.model

data class CredentialProfileRecord(
    val profileId: String,
    val alias: String,
    val tagsJson: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class CredentialSecretRecord(
    val profileId: String,
    val encryptedPayload: String,
    val encryptionVersion: Int,
    val updatedAt: Long,
)

data class CredentialSetItemRecord(
    val credentialSetId: String,
    val profileId: String,
    val orderNo: Int,
    val enabled: Boolean,
)

data class CredentialSetRecord(
    val credentialSetId: String,
    val name: String,
    val description: String?,
    val strategy: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val items: List<CredentialSetItemRecord>,
)
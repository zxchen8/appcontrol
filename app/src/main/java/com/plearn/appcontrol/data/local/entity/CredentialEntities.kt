package com.plearn.appcontrol.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "credential_profiles",
    primaryKeys = ["profileId"],
    indices = [Index(value = ["alias"]), Index(value = ["enabled"])],
)
data class CredentialProfileEntity(
    val profileId: String,
    val alias: String,
    val tagsJson: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "credential_secrets",
    primaryKeys = ["profileId"],
    foreignKeys = [
        ForeignKey(
            entity = CredentialProfileEntity::class,
            parentColumns = ["profileId"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class CredentialSecretEntity(
    val profileId: String,
    val encryptedPayload: String,
    val encryptionVersion: Int,
    val updatedAt: Long,
)

@Entity(
    tableName = "credential_sets",
    primaryKeys = ["credentialSetId"],
    indices = [Index(value = ["enabled"]), Index(value = ["strategy"])],
)
data class CredentialSetEntity(
    val credentialSetId: String,
    val name: String,
    val description: String?,
    val strategy: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "credential_set_items",
    primaryKeys = ["credentialSetId", "profileId"],
    foreignKeys = [
        ForeignKey(
            entity = CredentialSetEntity::class,
            parentColumns = ["credentialSetId"],
            childColumns = ["credentialSetId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CredentialProfileEntity::class,
            parentColumns = ["profileId"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["credentialSetId", "orderNo"], unique = true),
        Index(value = ["profileId"]),
    ],
)
data class CredentialSetItemEntity(
    val credentialSetId: String,
    val profileId: String,
    val orderNo: Int,
    val enabled: Boolean,
)
package com.plearn.appcontrol.data.local.entity

import androidx.room.Embedded
import androidx.room.Relation

data class CredentialSetWithItems(
    @Embedded val credentialSet: CredentialSetEntity,
    @Relation(
        parentColumn = "credentialSetId",
        entityColumn = "credentialSetId",
    )
    val items: List<CredentialSetItemEntity>,
)
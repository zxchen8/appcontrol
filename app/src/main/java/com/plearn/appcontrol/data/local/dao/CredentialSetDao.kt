package com.plearn.appcontrol.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.plearn.appcontrol.data.local.entity.CredentialSetEntity
import com.plearn.appcontrol.data.local.entity.CredentialSetItemEntity
import com.plearn.appcontrol.data.local.entity.CredentialSetWithItems

@Dao
abstract class CredentialSetDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertCredentialSet(credentialSet: CredentialSetEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertItems(items: List<CredentialSetItemEntity>)

    @Query("DELETE FROM credential_set_items WHERE credentialSetId = :credentialSetId")
    abstract suspend fun deleteItemsForSet(credentialSetId: String)

    @Transaction
    @Query("SELECT * FROM credential_sets WHERE credentialSetId = :credentialSetId LIMIT 1")
    abstract suspend fun getCredentialSetWithItems(credentialSetId: String): CredentialSetWithItems?

    @Query("SELECT * FROM credential_set_items WHERE credentialSetId = :credentialSetId ORDER BY orderNo ASC")
    abstract suspend fun getItemsForSet(credentialSetId: String): List<CredentialSetItemEntity>

    @Transaction
    open suspend fun replaceCredentialSet(
        credentialSet: CredentialSetEntity,
        items: List<CredentialSetItemEntity>,
    ) {
        upsertCredentialSet(credentialSet)
        deleteItemsForSet(credentialSet.credentialSetId)
        if (items.isNotEmpty()) {
            insertItems(items)
        }
    }
}
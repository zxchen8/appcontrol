package com.plearn.appcontrol.appservice

import com.plearn.appcontrol.data.model.CredentialProfileRecord
import com.plearn.appcontrol.data.model.CredentialSecretRecord
import com.plearn.appcontrol.data.model.CredentialSetItemRecord
import com.plearn.appcontrol.data.model.CredentialSetRecord
import com.plearn.appcontrol.data.repository.CredentialRepository
import com.plearn.appcontrol.data.repository.TaskRepository
import com.plearn.appcontrol.dsl.TaskDslParser
import com.plearn.appcontrol.dsl.TaskTrigger
import javax.inject.Inject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class CredentialManagementSnapshot(
    val profiles: List<CredentialProfileSummary>,
    val credentialSets: List<CredentialSetSummary>,
)

data class CredentialProfileSummary(
    val profileId: String,
    val alias: String,
    val tagsJson: String,
    val enabled: Boolean,
    val memberOfCredentialSetIds: List<String>,
)

data class CredentialSetSummary(
    val credentialSetId: String,
    val name: String,
    val description: String?,
    val strategy: String,
    val enabled: Boolean,
    val items: List<CredentialSetMemberSummary>,
    val referencingTasks: List<CredentialSetTaskReference>,
)

data class CredentialSetMemberSummary(
    val profileId: String,
    val alias: String,
    val itemEnabled: Boolean,
    val profileEnabled: Boolean,
)

data class CredentialSetTaskReference(
    val taskId: String,
    val taskName: String,
)

data class CredentialSetMemberInput(
    val profileId: String,
    val enabled: Boolean,
)

data class CredentialSaveResult(
    val saved: Boolean,
    val errorMessage: String? = null,
)

data class CredentialToggleResult(
    val updated: Boolean,
    val errorMessage: String? = null,
)

class CredentialManagementService @Inject constructor(
    private val credentialRepository: CredentialRepository,
    private val taskRepository: TaskRepository,
    private val parser: TaskDslParser = TaskDslParser(),
) {
    suspend fun loadSnapshot(): CredentialManagementSnapshot {
        val profiles = credentialRepository.listCredentialProfiles()
        val credentialSets = credentialRepository.listCredentialSets()
        val profileById = profiles.associateBy(CredentialProfileRecord::profileId)
        val memberOfSetIdsByProfileId = mutableMapOf<String, MutableList<String>>()
        credentialSets.forEach { credentialSet ->
            credentialSet.items.forEach { item ->
                memberOfSetIdsByProfileId.getOrPut(item.profileId) { mutableListOf() } += credentialSet.credentialSetId
            }
        }
        val referencingTasksBySetId = buildReferencingTasksBySetId()

        return CredentialManagementSnapshot(
            profiles = profiles.map { profile ->
                CredentialProfileSummary(
                    profileId = profile.profileId,
                    alias = profile.alias,
                    tagsJson = profile.tagsJson,
                    enabled = profile.enabled,
                    memberOfCredentialSetIds = memberOfSetIdsByProfileId[profile.profileId].orEmpty().sorted(),
                )
            },
            credentialSets = credentialSets.map { credentialSet ->
                CredentialSetSummary(
                    credentialSetId = credentialSet.credentialSetId,
                    name = credentialSet.name,
                    description = credentialSet.description,
                    strategy = credentialSet.strategy,
                    enabled = credentialSet.enabled,
                    items = credentialSet.items.sortedBy(CredentialSetItemRecord::orderNo).map { item ->
                        val profile = profileById[item.profileId]
                        CredentialSetMemberSummary(
                            profileId = item.profileId,
                            alias = profile?.alias ?: item.profileId,
                            itemEnabled = item.enabled,
                            profileEnabled = profile?.enabled ?: false,
                        )
                    },
                    referencingTasks = referencingTasksBySetId[credentialSet.credentialSetId].orEmpty(),
                )
            },
        )
    }

    suspend fun saveProfile(
        profileId: String,
        alias: String,
        tagsJson: String,
        enabled: Boolean,
        encryptedPayload: String?,
        encryptionVersion: Int,
    ): CredentialSaveResult {
        if (profileId.isBlank()) {
            return CredentialSaveResult(saved = false, errorMessage = "profileId is required.")
        }
        if (alias.isBlank()) {
            return CredentialSaveResult(saved = false, errorMessage = "alias is required.")
        }

        val nowMs = System.currentTimeMillis()
        val existing = credentialRepository.getCredentialProfile(profileId)
        credentialRepository.upsertCredentialProfile(
            CredentialProfileRecord(
                profileId = profileId,
                alias = alias,
                tagsJson = tagsJson.ifBlank { "[]" },
                enabled = enabled,
                createdAt = existing?.createdAt ?: nowMs,
                updatedAt = nowMs,
            ),
        )
        if (!encryptedPayload.isNullOrBlank()) {
            credentialRepository.upsertCredentialSecret(
                CredentialSecretRecord(
                    profileId = profileId,
                    encryptedPayload = encryptedPayload,
                    encryptionVersion = encryptionVersion,
                    updatedAt = nowMs,
                ),
            )
        }

        return CredentialSaveResult(saved = true)
    }

    suspend fun saveCredentialSet(
        credentialSetId: String,
        name: String,
        description: String?,
        strategy: String,
        enabled: Boolean,
        membersInOrder: List<CredentialSetMemberInput>,
    ): CredentialSaveResult {
        val profileIdsInOrder = membersInOrder.map(CredentialSetMemberInput::profileId)
        if (credentialSetId.isBlank()) {
            return CredentialSaveResult(saved = false, errorMessage = "credentialSetId is required.")
        }
        if (name.isBlank()) {
            return CredentialSaveResult(saved = false, errorMessage = "name is required.")
        }
        if (strategy.isBlank()) {
            return CredentialSaveResult(saved = false, errorMessage = "strategy is required.")
        }
        if (membersInOrder.any { it.profileId.isBlank() }) {
            return CredentialSaveResult(saved = false, errorMessage = "credential set members must provide profileId.")
        }
        if (profileIdsInOrder.distinct().size != profileIdsInOrder.size) {
            return CredentialSaveResult(saved = false, errorMessage = "credential set members must be unique.")
        }

        val knownProfileIds = credentialRepository.listCredentialProfiles().map(CredentialProfileRecord::profileId).toSet()
        val unknownProfileIds = profileIdsInOrder.filterNot { it in knownProfileIds }
        if (unknownProfileIds.isNotEmpty()) {
            return CredentialSaveResult(
                saved = false,
                errorMessage = "Unknown profileIds: ${unknownProfileIds.joinToString()}.",
            )
        }

        val nowMs = System.currentTimeMillis()
        val existing = credentialRepository.getCredentialSet(credentialSetId)
        credentialRepository.replaceCredentialSet(
            CredentialSetRecord(
                credentialSetId = credentialSetId,
                name = name,
                description = description,
                strategy = strategy,
                enabled = enabled,
                createdAt = existing?.createdAt ?: nowMs,
                updatedAt = nowMs,
                items = membersInOrder.mapIndexed { index, member ->
                    CredentialSetItemRecord(
                        credentialSetId = credentialSetId,
                        profileId = member.profileId,
                        orderNo = index,
                        enabled = member.enabled,
                    )
                },
            ),
        )

        return CredentialSaveResult(saved = true)
    }

    suspend fun setProfileEnabled(profileId: String, enabled: Boolean): CredentialToggleResult {
        val existing = credentialRepository.getCredentialProfile(profileId)
            ?: return CredentialToggleResult(updated = false, errorMessage = "Credential profile $profileId was not found.")
        credentialRepository.upsertCredentialProfile(
            existing.copy(
                enabled = enabled,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return CredentialToggleResult(updated = true)
    }

    suspend fun setCredentialSetEnabled(credentialSetId: String, enabled: Boolean): CredentialToggleResult {
        val existing = credentialRepository.getCredentialSet(credentialSetId)
            ?: return CredentialToggleResult(updated = false, errorMessage = "Credential set $credentialSetId was not found.")
        credentialRepository.replaceCredentialSet(
            existing.copy(
                enabled = enabled,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return CredentialToggleResult(updated = true)
    }

    private suspend fun buildReferencingTasksBySetId(): Map<String, List<CredentialSetTaskReference>> {
        val references = mutableMapOf<String, MutableList<CredentialSetTaskReference>>()
        for (definition in taskRepository.listTaskDefinitions()) {
            val parsedTask = parser.parse(definition.rawJson).task ?: continue
            if (parsedTask.trigger !is TaskTrigger.Continuous) {
                continue
            }

            val credentialSetId = parsedTask.accountRotation.credentialSetId() ?: continue
            references.getOrPut(credentialSetId) { mutableListOf() } += CredentialSetTaskReference(
                taskId = definition.taskId,
                taskName = definition.name,
            )
        }
        return references.mapValues { (_, refs) -> refs.sortedBy(CredentialSetTaskReference::taskName) }
    }

    private fun JsonObject?.credentialSetId(): String? =
        (this?.get("credentialSetId") as? JsonPrimitive)?.contentOrNull
}
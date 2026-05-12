package com.plearn.appcontrol.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.plearn.appcontrol.appservice.AppDashboardService
import com.plearn.appcontrol.appservice.AppDashboardSnapshot
import com.plearn.appcontrol.appservice.CredentialManagementService
import com.plearn.appcontrol.appservice.CredentialManagementSnapshot
import com.plearn.appcontrol.appservice.CredentialSetMemberInput
import com.plearn.appcontrol.appservice.CredentialProfileSummary
import com.plearn.appcontrol.appservice.CredentialSetSummary
import com.plearn.appcontrol.appservice.DeviceEnvironmentReport
import com.plearn.appcontrol.appservice.DeviceValidationErrorCode
import com.plearn.appcontrol.appservice.DeviceValidationResult
import com.plearn.appcontrol.appservice.DeviceValidationService
import com.plearn.appcontrol.appservice.RecentRunSummary
import com.plearn.appcontrol.appservice.RunningSessionSummary
import com.plearn.appcontrol.appservice.TaskDashboardItem
import com.plearn.appcontrol.appservice.TaskManagementService
import com.plearn.appcontrol.appservice.TaskMonitoringDetailService
import com.plearn.appcontrol.appservice.TaskMonitoringDetailSnapshot
import com.plearn.appcontrol.appservice.TapSmokeCheckRequest
import com.plearn.appcontrol.capability.ElementSelector
import com.plearn.appcontrol.capability.SelectorType
import com.plearn.appcontrol.diagnostics.toDiagnosticArtifactDisplayText
import com.plearn.appcontrol.runner.TaskRunStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun AppControlApp(
    deviceValidationService: DeviceValidationService,
    dashboardService: AppDashboardService,
    taskManagementService: TaskManagementService,
    taskMonitoringDetailService: TaskMonitoringDetailService,
    credentialManagementService: CredentialManagementService,
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var dashboardSnapshot by remember { mutableStateOf<AppDashboardSnapshot?>(null) }
    var dashboardLoading by remember { mutableStateOf(false) }
    var dashboardErrorText by rememberSaveable { mutableStateOf<String?>(null) }
    var environmentReport by remember { mutableStateOf<DeviceEnvironmentReport?>(null) }
    var validationResult by remember { mutableStateOf<DeviceValidationResult?>(null) }
    var environmentStatusText by rememberSaveable { mutableStateOf("尚未执行环境检查。") }
    var validationStatusText by rememberSaveable { mutableStateOf("尚未执行点击链路验证。") }
    var packageName by rememberSaveable { mutableStateOf("com.example.target") }
    var selectorValue by rememberSaveable { mutableStateOf("com.example.target:id/login_button") }
    var selectorType by rememberSaveable { mutableStateOf(SelectorType.RESOURCE_ID.name) }
    var taskEditorJson by rememberSaveable { mutableStateOf(defaultTaskImportJson) }
    var taskActionStatus by rememberSaveable { mutableStateOf("尚未执行任务导入或运行动作。") }
    var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedRunId by rememberSaveable { mutableStateOf<String?>(null) }
    var taskDetailSnapshot by remember { mutableStateOf<TaskMonitoringDetailSnapshot?>(null) }
    var taskDetailLoading by remember { mutableStateOf(false) }
    var taskDetailErrorText by rememberSaveable { mutableStateOf<String?>(null) }
    var credentialSnapshot by remember { mutableStateOf<CredentialManagementSnapshot?>(null) }
    var credentialLoading by remember { mutableStateOf(false) }
    var credentialActionStatus by rememberSaveable { mutableStateOf("尚未执行账号配置动作。") }
    var profileIdInput by rememberSaveable { mutableStateOf("") }
    var profileAliasInput by rememberSaveable { mutableStateOf("") }
    var profileTagsJsonInput by rememberSaveable { mutableStateOf("[]") }
    var profileEnabledInput by rememberSaveable { mutableStateOf(true) }
    var profileEncryptedPayloadInput by remember { mutableStateOf("") }
    var credentialSetIdInput by rememberSaveable { mutableStateOf("") }
    var credentialSetNameInput by rememberSaveable { mutableStateOf("") }
    var credentialSetDescriptionInput by rememberSaveable { mutableStateOf("") }
    var credentialSetEnabledInput by rememberSaveable { mutableStateOf(true) }
    var credentialSetProfileIdsInput by rememberSaveable { mutableStateOf("") }
    var actionInFlight by remember { mutableStateOf(false) }
    var pendingDashboardRefresh by remember { mutableStateOf(false) }
    var pendingCredentialRefresh by remember { mutableStateOf(false) }

    suspend fun refreshCredentialSnapshot() {
        if (credentialLoading) {
            pendingCredentialRefresh = true
            return
        }
        do {
            pendingCredentialRefresh = false
            credentialLoading = true
            try {
                credentialSnapshot = credentialManagementService.loadSnapshot()
            } finally {
                credentialLoading = false
            }
        } while (pendingCredentialRefresh)
    }

    fun syncProfileEditorEnabledState(profileId: String, enabled: Boolean) {
        if (profileIdInput == profileId) {
            profileEnabledInput = enabled
        }
    }

    fun syncCredentialSetEditorState(credentialSet: CredentialSetSummary) {
        if (credentialSetIdInput == credentialSet.credentialSetId) {
            credentialSetEnabledInput = credentialSet.enabled
        }
    }

    suspend fun refreshTaskDetail(
        targetTaskId: String? = selectedTaskId,
        targetRunId: String? = selectedRunId,
    ) {
        if (targetTaskId == null) {
            taskDetailSnapshot = null
            taskDetailErrorText = null
            selectedTaskId = null
            selectedRunId = null
            return
        }

        taskDetailLoading = true
        try {
            val detail = taskMonitoringDetailService.loadTaskDetail(
                taskId = targetTaskId,
                selectedRunId = targetRunId,
            )
            taskDetailSnapshot = detail
            selectedTaskId = targetTaskId
            selectedRunId = detail?.selectedRun?.runId
            taskDetailErrorText = null
        } catch (error: Exception) {
            if (error is CancellationException) {
                throw error
            }
            taskDetailSnapshot = null
            selectedTaskId = targetTaskId
            selectedRunId = targetRunId
            taskDetailErrorText = error.message ?: "任务详情加载失败。"
        } finally {
            taskDetailLoading = false
        }
    }

    suspend fun refreshDashboardSnapshot() {
        if (dashboardLoading) {
            pendingDashboardRefresh = true
            return
        }
        do {
            pendingDashboardRefresh = false
            dashboardLoading = true
            try {
                val snapshot = dashboardService.loadSnapshot()
                dashboardSnapshot = snapshot
                dashboardErrorText = null
                val nextSelectedTaskId = when {
                    snapshot.tasks.isEmpty() -> null
                    selectedTaskId != null && snapshot.tasks.any { task -> task.taskId == selectedTaskId } -> selectedTaskId
                    else -> snapshot.tasks.first().taskId
                }
                val nextSelectedRunId = if (nextSelectedTaskId == selectedTaskId) selectedRunId else null
                selectedTaskId = nextSelectedTaskId
                selectedRunId = nextSelectedRunId
                refreshTaskDetail(
                    targetTaskId = nextSelectedTaskId,
                    targetRunId = nextSelectedRunId,
                )
                refreshCredentialSnapshot()
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }
                dashboardErrorText = error.message ?: "调度概览加载失败。"
            } finally {
                dashboardLoading = false
            }
        } while (pendingDashboardRefresh)
    }

    fun refreshDashboard() {
        scope.launch {
            refreshDashboardSnapshot()
        }
    }

    fun launchTaskAction(
        refreshAfter: Boolean = true,
        action: suspend () -> String,
    ) {
        if (actionInFlight) {
            return
        }
        scope.launch {
            actionInFlight = true
            try {
                taskActionStatus = action()
                if (refreshAfter) {
                    refreshDashboardSnapshot()
                }
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }
                taskActionStatus = error.message ?: "任务动作执行失败。"
            } finally {
                actionInFlight = false
            }
        }
    }

    fun launchCredentialAction(
        action: suspend () -> String,
    ) {
        if (actionInFlight) {
            return
        }
        scope.launch {
            actionInFlight = true
            try {
                credentialActionStatus = action()
                refreshCredentialSnapshot()
            } catch (error: Exception) {
                if (error is CancellationException) {
                    throw error
                }
                credentialActionStatus = error.message ?: "账号配置动作执行失败。"
            } finally {
                actionInFlight = false
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshDashboard()
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "AppControl",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "当前已接入前台调度运行时、任务读侧概览与设备验证入口。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    StatusCard(
                        title = "当前状态",
                        body = "支持 scheduler 前台服务恢复链、任务导入/启停/手动运行、任务列表读侧、最近运行记录概览，以及环境检查与点击链路 smoke 验证。",
                    )
                    StatusCard(
                        title = "下一步",
                        body = "继续补监控详情页、运行结果反馈细化和更完整的 Phase 5 稳定性验证。",
                    )
                    TaskManagementCard(
                        taskEditorJson = taskEditorJson,
                        onTaskEditorJsonChange = { taskEditorJson = it },
                        actionInFlight = actionInFlight,
                        taskActionStatus = taskActionStatus,
                        onImportTask = {
                            launchTaskAction {
                                val result = taskManagementService.importTask(taskEditorJson)
                                if (result.saved) {
                                    selectedTaskId = result.taskId
                                    selectedRunId = null
                                    "任务 ${result.name ?: result.taskId} 已导入，状态=${result.definitionStatus}。"
                                } else {
                                    "导入失败：\n${result.errors.joinToString(separator = "\n")}" 
                                }
                            }
                        },
                        onClearEditor = {
                            taskEditorJson = defaultTaskImportJson
                            taskActionStatus = "已恢复默认任务 JSON 模板。"
                        },
                    )
                    CredentialConfigurationCard(
                        snapshot = credentialSnapshot,
                        loading = credentialLoading,
                        actionInFlight = actionInFlight,
                        credentialActionStatus = credentialActionStatus,
                        profileIdInput = profileIdInput,
                        onProfileIdInputChange = { profileIdInput = it },
                        profileAliasInput = profileAliasInput,
                        onProfileAliasInputChange = { profileAliasInput = it },
                        profileTagsJsonInput = profileTagsJsonInput,
                        onProfileTagsJsonInputChange = { profileTagsJsonInput = it },
                        profileEnabledInput = profileEnabledInput,
                        onProfileEnabledInputChange = { profileEnabledInput = it },
                        profileEncryptedPayloadInput = profileEncryptedPayloadInput,
                        onProfileEncryptedPayloadInputChange = { profileEncryptedPayloadInput = it },
                        credentialSetIdInput = credentialSetIdInput,
                        onCredentialSetIdInputChange = { credentialSetIdInput = it },
                        credentialSetNameInput = credentialSetNameInput,
                        onCredentialSetNameInputChange = { credentialSetNameInput = it },
                        credentialSetDescriptionInput = credentialSetDescriptionInput,
                        onCredentialSetDescriptionInputChange = { credentialSetDescriptionInput = it },
                        credentialSetEnabledInput = credentialSetEnabledInput,
                        onCredentialSetEnabledInputChange = { credentialSetEnabledInput = it },
                        credentialSetProfileIdsInput = credentialSetProfileIdsInput,
                        onCredentialSetProfileIdsInputChange = { credentialSetProfileIdsInput = it },
                        onSaveProfile = {
                            launchCredentialAction {
                                val result = credentialManagementService.saveProfile(
                                    profileId = profileIdInput,
                                    alias = profileAliasInput,
                                    tagsJson = profileTagsJsonInput,
                                    enabled = profileEnabledInput,
                                    encryptedPayload = profileEncryptedPayloadInput,
                                    encryptionVersion = DEFAULT_ENCRYPTION_VERSION,
                                )
                                if (result.saved) {
                                    profileEncryptedPayloadInput = ""
                                    "账号 ${if (profileAliasInput.isBlank()) profileIdInput else profileAliasInput} 已保存。"
                                } else {
                                    result.errorMessage ?: "账号保存失败。"
                                }
                            }
                        },
                        onResetProfileEditor = {
                            profileIdInput = ""
                            profileAliasInput = ""
                            profileTagsJsonInput = "[]"
                            profileEnabledInput = true
                            profileEncryptedPayloadInput = ""
                            credentialActionStatus = "已清空账号编辑区。"
                        },
                        onLoadProfile = { profile ->
                            profileIdInput = profile.profileId
                            profileAliasInput = profile.alias
                            profileTagsJsonInput = profile.tagsJson
                            profileEnabledInput = profile.enabled
                            profileEncryptedPayloadInput = ""
                            credentialActionStatus = "已载入账号 ${profile.alias}，密文载荷不会回显。"
                        },
                        onToggleProfileEnabled = { profile ->
                            launchCredentialAction {
                                val result = credentialManagementService.setProfileEnabled(
                                    profileId = profile.profileId,
                                    enabled = !profile.enabled,
                                )
                                if (result.updated) {
                                    syncProfileEditorEnabledState(
                                        profileId = profile.profileId,
                                        enabled = !profile.enabled,
                                    )
                                    "账号 ${profile.alias} 已${if (!profile.enabled) "启用" else "停用"}。"
                                } else {
                                    result.errorMessage ?: "账号 ${profile.profileId} 无法更新启用状态。"
                                }
                            }
                        },
                        onSaveCredentialSet = {
                            launchCredentialAction {
                                val membersInOrder = credentialSetProfileIdsInput.toCredentialSetMembers()
                                val result = credentialManagementService.saveCredentialSet(
                                    credentialSetId = credentialSetIdInput,
                                    name = credentialSetNameInput,
                                    description = credentialSetDescriptionInput.ifBlank { null },
                                    strategy = DEFAULT_CREDENTIAL_SET_STRATEGY,
                                    enabled = credentialSetEnabledInput,
                                    membersInOrder = membersInOrder,
                                )
                                if (result.saved) {
                                    "账号组 ${if (credentialSetNameInput.isBlank()) credentialSetIdInput else credentialSetNameInput} 已保存。"
                                } else {
                                    result.errorMessage ?: "账号组保存失败。"
                                }
                            }
                        },
                        onResetCredentialSetEditor = {
                            credentialSetIdInput = ""
                            credentialSetNameInput = ""
                            credentialSetDescriptionInput = ""
                            credentialSetEnabledInput = true
                            credentialSetProfileIdsInput = ""
                            credentialActionStatus = "已清空账号组编辑区。"
                        },
                        onLoadCredentialSet = { credentialSet ->
                            credentialSetIdInput = credentialSet.credentialSetId
                            credentialSetNameInput = credentialSet.name
                            credentialSetDescriptionInput = credentialSet.description.orEmpty()
                            credentialSetEnabledInput = credentialSet.enabled
                            credentialSetProfileIdsInput = credentialSet.items.toEditorText()
                            credentialActionStatus = "已载入账号组 ${credentialSet.name}，成员启停状态已写入编辑文本。"
                        },
                        onToggleCredentialSetEnabled = { credentialSet ->
                            launchCredentialAction {
                                val result = credentialManagementService.setCredentialSetEnabled(
                                    credentialSetId = credentialSet.credentialSetId,
                                    enabled = !credentialSet.enabled,
                                )
                                if (result.updated) {
                                    syncCredentialSetEditorState(credentialSet.copy(enabled = !credentialSet.enabled))
                                    "账号组 ${credentialSet.name} 已${if (!credentialSet.enabled) "启用" else "停用"}。"
                                } else {
                                    result.errorMessage ?: "账号组 ${credentialSet.credentialSetId} 无法更新启用状态。"
                                }
                            }
                        },
                    )
                    DashboardOverviewCard(
                        snapshot = dashboardSnapshot,
                        loading = dashboardLoading,
                        errorText = dashboardErrorText,
                        onRefresh = ::refreshDashboard,
                    )
                    TaskListCard(
                        tasks = dashboardSnapshot?.tasks.orEmpty(),
                        selectedTaskId = selectedTaskId,
                        actionInFlight = actionInFlight,
                        taskDetailLoading = taskDetailLoading,
                        onSelectTask = { task ->
                            if (taskDetailLoading) {
                                return@TaskListCard
                            }
                            scope.launch {
                                selectedTaskId = task.taskId
                                selectedRunId = null
                                refreshTaskDetail(
                                    targetTaskId = task.taskId,
                                    targetRunId = null,
                                )
                            }
                        },
                        onLoadTaskJson = { task ->
                            launchTaskAction(refreshAfter = false) {
                                val rawJson = taskManagementService.loadTaskRawJson(task.taskId)
                                if (rawJson == null) {
                                    "未找到任务 ${task.taskId} 的原始 JSON。"
                                } else {
                                    taskEditorJson = rawJson
                                    "已载入任务 ${task.taskId} 的原始 JSON，可直接编辑并重新导入。"
                                }
                            }
                        },
                        onToggleTaskEnabled = { task ->
                            launchTaskAction {
                                selectedTaskId = task.taskId
                                val result = taskManagementService.setTaskEnabled(
                                    taskId = task.taskId,
                                    enabled = !task.enabled,
                                )
                                if (result.updated) {
                                    "任务 ${task.taskId} 已${if (result.enabled) "启用" else "停用"}。"
                                } else {
                                    result.errorMessage ?: "任务 ${task.taskId} 无法更新启用状态。"
                                }
                            }
                        },
                        onRunTaskNow = { task ->
                            launchTaskAction {
                                selectedTaskId = task.taskId
                                val result = taskManagementService.runTaskNow(task.taskId)
                                result.errorMessage ?: buildString {
                                    append("任务 ${task.taskId} 手动运行结果=${result.execution?.taskRun?.status ?: "unknown"}")
                                    result.execution?.taskRun?.errorCode?.let { append(" | error=$it") }
                                    result.execution?.taskRun?.message?.let { append(" | $it") }
                                }
                            }
                        },
                    )
                    TaskMonitoringDetailCard(
                        snapshot = taskDetailSnapshot,
                        selectedTaskId = selectedTaskId,
                        loading = taskDetailLoading,
                        errorText = taskDetailErrorText,
                        actionInFlight = actionInFlight,
                        onSelectRun = { runId ->
                            val taskId = selectedTaskId ?: return@TaskMonitoringDetailCard
                            if (taskDetailLoading) {
                                return@TaskMonitoringDetailCard
                            }
                            scope.launch {
                                selectedRunId = runId
                                refreshTaskDetail(
                                    targetTaskId = taskId,
                                    targetRunId = runId,
                                )
                            }
                        },
                    )
                    RunningSessionsCard(runningSessions = dashboardSnapshot?.runningSessions.orEmpty())
                    RecentRunsCard(recentRuns = dashboardSnapshot?.recentRuns.orEmpty())
                    StatusCard(
                        title = "环境检查",
                        body = environmentReport?.toDisplayText() ?: environmentStatusText,
                    )
                    ValidationCard(
                        packageName = packageName,
                        onPackageNameChange = { packageName = it },
                        selectorValue = selectorValue,
                        onSelectorValueChange = { selectorValue = it },
                        selectorType = selectorType,
                        onSelectorTypeChange = { selectorType = it },
                        actionInFlight = actionInFlight,
                        lastValidationText = validationResult?.toDisplayText() ?: validationStatusText,
                        onInspectEnvironment = {
                            scope.launch {
                                actionInFlight = true
                                try {
                                    environmentReport = deviceValidationService.inspectEnvironment()
                                    environmentStatusText = environmentReport?.toDisplayText() ?: "尚未执行环境检查。"
                                } catch (error: Exception) {
                                    if (error is CancellationException) {
                                        throw error
                                    }
                                    environmentReport = null
                                    environmentStatusText = error.message ?: "环境检查失败。"
                                } finally {
                                    actionInFlight = false
                                }
                            }
                        },
                        onRunSmokeCheck = {
                            scope.launch {
                                actionInFlight = true
                                try {
                                    validationResult = deviceValidationService.runTapSmokeCheck(
                                        TapSmokeCheckRequest(
                                            packageName = packageName,
                                            selector = ElementSelector(
                                                by = selectorType.toSelectorType(),
                                                value = selectorValue,
                                            ),
                                        ),
                                    )
                                    environmentReport = validationResult?.environment
                                    environmentStatusText = environmentReport?.toDisplayText() ?: environmentStatusText
                                    validationStatusText = validationResult?.toDisplayText() ?: "尚未执行点击链路验证。"
                                } catch (error: Exception) {
                                    if (error is CancellationException) {
                                        throw error
                                    }
                                    validationResult = null
                                    environmentReport = null
                                    validationStatusText = error.message ?: "点击链路验证失败。"
                                    environmentStatusText = "最近一次环境报告不可用。"
                                } finally {
                                    actionInFlight = false
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun StatusCard(title: String, body: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(text = body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskManagementCard(
    taskEditorJson: String,
    onTaskEditorJsonChange: (String) -> Unit,
    actionInFlight: Boolean,
    taskActionStatus: String,
    onImportTask: () -> Unit,
    onClearEditor: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "任务写入口", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "当前入口只接受可通过校验的任务 JSON。校验失败会返回错误，但不会落库。",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedTextField(
                value = taskEditorJson,
                onValueChange = onTaskEditorJsonChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("task-editor-json"),
                label = { Text("任务 JSON") },
                minLines = 12,
                enabled = !actionInFlight,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onImportTask, enabled = !actionInFlight) {
                    Text("导入或更新任务")
                }
                Button(onClick = onClearEditor, enabled = !actionInFlight) {
                    Text("恢复模板")
                }
            }
            Text(text = taskActionStatus, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CredentialConfigurationCard(
    snapshot: CredentialManagementSnapshot?,
    loading: Boolean,
    actionInFlight: Boolean,
    credentialActionStatus: String,
    profileIdInput: String,
    onProfileIdInputChange: (String) -> Unit,
    profileAliasInput: String,
    onProfileAliasInputChange: (String) -> Unit,
    profileTagsJsonInput: String,
    onProfileTagsJsonInputChange: (String) -> Unit,
    profileEnabledInput: Boolean,
    onProfileEnabledInputChange: (Boolean) -> Unit,
    profileEncryptedPayloadInput: String,
    onProfileEncryptedPayloadInputChange: (String) -> Unit,
    credentialSetIdInput: String,
    onCredentialSetIdInputChange: (String) -> Unit,
    credentialSetNameInput: String,
    onCredentialSetNameInputChange: (String) -> Unit,
    credentialSetDescriptionInput: String,
    onCredentialSetDescriptionInputChange: (String) -> Unit,
    credentialSetEnabledInput: Boolean,
    onCredentialSetEnabledInputChange: (Boolean) -> Unit,
    credentialSetProfileIdsInput: String,
    onCredentialSetProfileIdsInputChange: (String) -> Unit,
    onSaveProfile: () -> Unit,
    onResetProfileEditor: () -> Unit,
    onLoadProfile: (CredentialProfileSummary) -> Unit,
    onToggleProfileEnabled: (CredentialProfileSummary) -> Unit,
    onSaveCredentialSet: () -> Unit,
    onResetCredentialSetEditor: () -> Unit,
    onLoadCredentialSet: (CredentialSetSummary) -> Unit,
    onToggleCredentialSetEnabled: (CredentialSetSummary) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "账号配置", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "首版支持账号 profile 与账号组的新增、启停、顺序维护和任务引用查看。密文载荷只写入，不回显。",
                style = MaterialTheme.typography.bodyMedium,
            )

            Text(text = "账号编辑", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = profileIdInput,
                onValueChange = onProfileIdInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("profileId") },
                enabled = !actionInFlight,
            )
            OutlinedTextField(
                value = profileAliasInput,
                onValueChange = onProfileAliasInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("别名") },
                enabled = !actionInFlight,
            )
            OutlinedTextField(
                value = profileTagsJsonInput,
                onValueChange = onProfileTagsJsonInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("tagsJson") },
                enabled = !actionInFlight,
            )
            OutlinedTextField(
                value = profileEncryptedPayloadInput,
                onValueChange = onProfileEncryptedPayloadInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("encryptedPayload（可选，仅写入）") },
                enabled = !actionInFlight,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = profileEnabledInput,
                    onClick = { onProfileEnabledInputChange(!profileEnabledInput) },
                    label = { Text(if (profileEnabledInput) "Profile 已启用" else "Profile 已停用") },
                    enabled = !actionInFlight,
                )
                Button(onClick = onSaveProfile, enabled = !actionInFlight) {
                    Text("保存账号")
                }
                Button(onClick = onResetProfileEditor, enabled = !actionInFlight) {
                    Text("清空账号编辑")
                }
            }

            Text(text = "账号组编辑", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = credentialSetIdInput,
                onValueChange = onCredentialSetIdInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("credentialSetId") },
                enabled = !actionInFlight,
            )
            OutlinedTextField(
                value = credentialSetNameInput,
                onValueChange = onCredentialSetNameInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("账号组名称") },
                enabled = !actionInFlight,
            )
            OutlinedTextField(
                value = credentialSetDescriptionInput,
                onValueChange = onCredentialSetDescriptionInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("账号组描述") },
                enabled = !actionInFlight,
            )
            OutlinedTextField(
                value = credentialSetProfileIdsInput,
                onValueChange = onCredentialSetProfileIdsInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("成员（每行一个，格式 profileId|true/false）") },
                minLines = 4,
                enabled = !actionInFlight,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = credentialSetEnabledInput,
                    onClick = { onCredentialSetEnabledInputChange(!credentialSetEnabledInput) },
                    label = { Text(if (credentialSetEnabledInput) "账号组已启用" else "账号组已停用") },
                    enabled = !actionInFlight,
                )
                Button(onClick = onSaveCredentialSet, enabled = !actionInFlight) {
                    Text("保存账号组")
                }
                Button(onClick = onResetCredentialSetEditor, enabled = !actionInFlight) {
                    Text("清空账号组编辑")
                }
            }

            Text(text = credentialActionStatus, style = MaterialTheme.typography.bodyMedium)

            if (loading) {
                Text(text = "正在加载账号配置快照…", style = MaterialTheme.typography.bodyMedium)
            }

            Text(text = "账号列表", style = MaterialTheme.typography.titleSmall)
            if (snapshot?.profiles.isNullOrEmpty()) {
                Text(text = "当前没有账号 profile。", style = MaterialTheme.typography.bodyMedium)
            } else {
                snapshot?.profiles.orEmpty().forEach { profile ->
                    CredentialProfileRow(
                        profile = profile,
                        actionInFlight = actionInFlight,
                        onLoadProfile = onLoadProfile,
                        onToggleProfileEnabled = onToggleProfileEnabled,
                    )
                }
            }

            Text(text = "账号组列表", style = MaterialTheme.typography.titleSmall)
            if (snapshot?.credentialSets.isNullOrEmpty()) {
                Text(text = "当前没有账号组。", style = MaterialTheme.typography.bodyMedium)
            } else {
                snapshot?.credentialSets.orEmpty().forEach { credentialSet ->
                    CredentialSetRow(
                        credentialSet = credentialSet,
                        actionInFlight = actionInFlight,
                        onLoadCredentialSet = onLoadCredentialSet,
                        onToggleCredentialSetEnabled = onToggleCredentialSetEnabled,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CredentialProfileRow(
    profile: CredentialProfileSummary,
    actionInFlight: Boolean,
    onLoadProfile: (CredentialProfileSummary) -> Unit,
    onToggleProfileEnabled: (CredentialProfileSummary) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "${profile.alias} (${profile.profileId})", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "enabled=${profile.enabled} | tags=${profile.tagsJson}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "memberOf=${if (profile.memberOfCredentialSetIds.isEmpty()) "无" else profile.memberOfCredentialSetIds.joinToString()}",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onLoadProfile(profile) }, enabled = !actionInFlight) {
                    Text("载入账号")
                }
                Button(onClick = { onToggleProfileEnabled(profile) }, enabled = !actionInFlight) {
                    Text(if (profile.enabled) "停用账号" else "启用账号")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CredentialSetRow(
    credentialSet: CredentialSetSummary,
    actionInFlight: Boolean,
    onLoadCredentialSet: (CredentialSetSummary) -> Unit,
    onToggleCredentialSetEnabled: (CredentialSetSummary) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "${credentialSet.name} (${credentialSet.credentialSetId})",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "enabled=${credentialSet.enabled} | strategy=${credentialSet.strategy}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "items=${if (credentialSet.items.isEmpty()) "无" else credentialSet.items.joinToString { item -> item.toDisplayText() }}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "referencingTasks=${if (credentialSet.referencingTasks.isEmpty()) "无" else credentialSet.referencingTasks.joinToString { task -> task.taskName }}",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onLoadCredentialSet(credentialSet) }, enabled = !actionInFlight) {
                    Text("载入账号组")
                }
                Button(onClick = { onToggleCredentialSetEnabled(credentialSet) }, enabled = !actionInFlight) {
                    Text(if (credentialSet.enabled) "停用账号组" else "启用账号组")
                }
            }
        }
    }
}

@Composable
private fun DashboardOverviewCard(
    snapshot: AppDashboardSnapshot?,
    loading: Boolean,
    errorText: String?,
    onRefresh: () -> Unit,
) {
    val tasks = snapshot?.tasks.orEmpty()
    val enabledReadyCount = tasks.count { it.enabled && it.definitionStatus.equals("ready", ignoreCase = true) }
    val runningCount = snapshot?.runningSessions?.size ?: 0
    val recentFailureCount = snapshot?.recentRuns?.count { run ->
        run.status.equals("failed", ignoreCase = true) ||
            run.status.equals("blocked", ignoreCase = true) ||
            run.status.equals("timed_out", ignoreCase = true)
    } ?: 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "调度概览", style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (snapshot == null && loading) {
                    "正在加载任务与运行概览…"
                } else if (snapshot == null && errorText != null) {
                    "调度概览加载失败。"
                } else {
                    "任务 ${tasks.size} 个，已启用且 ready ${enabledReadyCount} 个，运行中会话 ${runningCount} 个，最近失败 ${recentFailureCount} 次。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (errorText != null) {
                Text(text = errorText, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onRefresh, enabled = !loading) {
                Text(if (loading) "刷新中…" else "刷新概览")
            }
        }
    }
}

@Composable
private fun TaskListCard(
    tasks: List<TaskDashboardItem>,
    selectedTaskId: String?,
    actionInFlight: Boolean,
    taskDetailLoading: Boolean,
    onSelectTask: (TaskDashboardItem) -> Unit,
    onLoadTaskJson: (TaskDashboardItem) -> Unit,
    onToggleTaskEnabled: (TaskDashboardItem) -> Unit,
    onRunTaskNow: (TaskDashboardItem) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "任务列表", style = MaterialTheme.typography.titleMedium)
            if (tasks.isEmpty()) {
                Text(text = "当前没有任务定义。", style = MaterialTheme.typography.bodyMedium)
            } else {
                tasks.forEach { task ->
                    TaskDashboardRow(
                        task = task,
                        selected = selectedTaskId == task.taskId,
                        actionInFlight = actionInFlight,
                        taskDetailLoading = taskDetailLoading,
                        onSelectTask = onSelectTask,
                        onLoadTaskJson = onLoadTaskJson,
                        onToggleTaskEnabled = onToggleTaskEnabled,
                        onRunTaskNow = onRunTaskNow,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskDashboardRow(
    task: TaskDashboardItem,
    selected: Boolean,
    actionInFlight: Boolean,
    taskDetailLoading: Boolean,
    onSelectTask: (TaskDashboardItem) -> Unit,
    onLoadTaskJson: (TaskDashboardItem) -> Unit,
    onToggleTaskEnabled: (TaskDashboardItem) -> Unit,
    onRunTaskNow: (TaskDashboardItem) -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("task-row-${task.taskId}"),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(text = "${task.name} (${task.taskId})", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "enabled=${task.enabled} | definition=${task.definitionStatus} | trigger=${task.triggerType}",
                modifier = Modifier.testTag(
                    "task-state-${task.taskId}-${if (task.enabled) "enabled" else "disabled"}",
                ),
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "standby=${task.standbyEnabled} | next=${formatTimestamp(task.nextTriggerAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "latest=${task.latestRunStatus ?: "无"}${task.latestRunTriggerType?.let { " ($it)" } ?: ""} @ ${formatTimestamp(task.latestRunStartedAt)}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (task.latestRunErrorCode != null) {
                Text(
                    text = "lastError=${task.latestRunErrorCode}${task.latestRunMessage?.let { " | $it" } ?: ""}",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (task.latestRunMessage != null) {
                Text(
                    text = "lastMessage=${task.latestRunMessage}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (task.runningSession != null) {
                Text(
                    text = "running session=${task.runningSession.sessionId} | current=${task.runningSession.currentCredentialAlias ?: "未标记"} | cycles=${task.runningSession.totalCycles}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { onSelectTask(task) },
                    modifier = Modifier.testTag("task-detail-${task.taskId}"),
                    enabled = !actionInFlight && !taskDetailLoading,
                ) {
                    Text(if (selected) "当前详情" else "查看详情")
                }
                Button(
                    onClick = { onToggleTaskEnabled(task) },
                    modifier = Modifier.testTag(
                        "task-toggle-${task.taskId}-${if (task.enabled) "disable" else "enable"}",
                    ),
                    enabled = !actionInFlight,
                ) {
                    Text(if (task.enabled) "停用" else "启用")
                }
                Button(
                    onClick = { onRunTaskNow(task) },
                    modifier = Modifier.testTag("task-run-now-${task.taskId}"),
                    enabled = !actionInFlight,
                ) {
                    Text("手动运行")
                }
                Button(
                    onClick = { onLoadTaskJson(task) },
                    modifier = Modifier.testTag("task-load-json-${task.taskId}"),
                    enabled = !actionInFlight,
                ) {
                    Text("载入 JSON")
                }
            }
        }
    }
}

@Composable
private fun TaskMonitoringDetailCard(
    snapshot: TaskMonitoringDetailSnapshot?,
    selectedTaskId: String?,
    loading: Boolean,
    errorText: String?,
    actionInFlight: Boolean,
    onSelectRun: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "任务监控详情", style = MaterialTheme.typography.titleMedium)
            when {
                loading -> {
                    Text(text = "正在加载任务详情…", style = MaterialTheme.typography.bodyMedium)
                }

                selectedTaskId == null -> {
                    Text(text = "当前没有可查看详情的任务。", style = MaterialTheme.typography.bodyMedium)
                }

                errorText != null -> {
                    Text(text = errorText, style = MaterialTheme.typography.bodyMedium)
                }

                snapshot == null -> {
                    Text(text = "未能加载任务 $selectedTaskId 的详情。", style = MaterialTheme.typography.bodyMedium)
                }

                else -> {
                    Text(
                        text = "${snapshot.definition.name} (${snapshot.definition.taskId})",
                        modifier = Modifier.testTag("task-detail-definition-${snapshot.definition.taskId}"),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "enabled=${snapshot.definition.enabled} | definition=${snapshot.definition.definitionStatus} | trigger=${snapshot.definition.triggerType}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = "standby=${snapshot.scheduleState?.standbyEnabled ?: false} | next=${formatTimestamp(snapshot.scheduleState?.nextTriggerAt)} | lastTrigger=${formatTimestamp(snapshot.scheduleState?.lastTriggerAt)} | schedule=${snapshot.scheduleState?.lastScheduleStatus ?: "idle"}",
                        modifier = Modifier.testTag("task-detail-schedule-${snapshot.definition.taskId}"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (snapshot.runningSession != null) {
                        Text(
                            text = "running session=${snapshot.runningSession.sessionId} | cycles=${snapshot.runningSession.totalCycles} | current=${snapshot.runningSession.currentCredentialAlias ?: "未标记"} | next=${snapshot.runningSession.nextCredentialAlias ?: "未标记"}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(text = "诊断策略", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "captureFailure=${snapshot.diagnostics.captureScreenshotOnFailure} | captureStepFailure=${snapshot.diagnostics.captureScreenshotOnStepFailure} | logLevel=${snapshot.diagnostics.logLevel}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (snapshot.recentDiagnosticsEvents.isNotEmpty()) {
                        Text(text = "最近诊断事件", style = MaterialTheme.typography.titleSmall)
                        snapshot.recentDiagnosticsEvents.forEach { event ->
                            Text(
                                text = event,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Text(text = "失败上下文", style = MaterialTheme.typography.titleSmall)
                    if (snapshot.failureContext == null) {
                        Text(text = "当前选中运行没有失败上下文。", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(
                            text = "run=${snapshot.failureContext.runId} | status=${snapshot.failureContext.status} | failedSteps=${snapshot.failureContext.failedStepCount}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (snapshot.failureContext.runErrorCode != null || snapshot.failureContext.runMessage != null) {
                            Text(
                                text = "runError=${snapshot.failureContext.runErrorCode ?: "none"}${snapshot.failureContext.runMessage?.let { " | $it" } ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (snapshot.failureContext.primaryFailedStepId != null) {
                            Text(
                                text = "step=${snapshot.failureContext.primaryFailedStepId}${snapshot.failureContext.primaryFailedStepErrorCode?.let { " | error=$it" } ?: ""}${snapshot.failureContext.primaryFailedStepMessage?.let { " | $it" } ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        snapshot.failureContext.runArtifacts.forEach { artifact ->
                            Text(
                                text = artifact,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        snapshot.failureContext.stepArtifacts.forEach { artifact ->
                            Text(
                                text = artifact,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    Text(text = "最近运行记录", style = MaterialTheme.typography.titleSmall)
                    if (snapshot.recentRuns.isEmpty()) {
                        Text(text = "当前任务还没有运行记录。", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        snapshot.recentRuns.forEach { run ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (snapshot.selectedRun?.runId == run.runId) {
                                        MaterialTheme.colorScheme.primaryContainer
                                    } else {
                                        MaterialTheme.colorScheme.surface
                                    },
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "run=${run.runId} | status=${run.status} | trigger=${run.triggerType}",
                                        modifier = if (snapshot.selectedRun?.runId == run.runId) {
                                            Modifier.testTag("task-detail-selected-run-${snapshot.definition.taskId}")
                                        } else {
                                            Modifier
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        text = "started=${formatTimestamp(run.startedAt)} | finished=${formatTimestamp(run.finishedAt)} | credential=${run.credentialAlias ?: "未标记"}",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (run.errorCode != null) {
                                        Text(
                                            text = "error=${run.errorCode}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Button(
                                        onClick = { onSelectRun(run.runId) },
                                        enabled = !loading && !actionInFlight,
                                    ) {
                                        Text(if (snapshot.selectedRun?.runId == run.runId) "当前步骤详情" else "查看步骤详情")
                                    }
                                }
                            }
                        }
                    }
                    Text(text = "步骤记录", style = MaterialTheme.typography.titleSmall)
                    if (snapshot.selectedRun == null) {
                        Text(text = "当前没有选中的运行记录。", style = MaterialTheme.typography.bodyMedium)
                    } else if (snapshot.stepRuns.isEmpty()) {
                        Text(
                            text = "运行 ${snapshot.selectedRun.runId} 当前没有步骤记录。",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        snapshot.stepRuns.forEach { step ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "step=${step.stepId} | status=${step.status}",
                                        modifier = Modifier.testTag(
                                            "task-detail-step-${snapshot.definition.taskId}-${step.stepId}",
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    Text(
                                        text = "started=${formatTimestamp(step.startedAt)} | finished=${formatTimestamp(step.finishedAt)} | duration=${step.durationMs ?: 0}ms",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                    if (step.errorCode != null) {
                                        Text(
                                            text = "error=${step.errorCode}${step.message?.let { " | $it" } ?: ""}",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    step.artifactsJson.toArtifactsDisplayText()?.let { artifactsText ->
                                        Text(
                                            text = "artifacts=$artifactsText",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RunningSessionsCard(runningSessions: List<RunningSessionSummary>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "运行中会话", style = MaterialTheme.typography.titleMedium)
            if (runningSessions.isEmpty()) {
                Text(text = "当前没有 running continuous session。", style = MaterialTheme.typography.bodyMedium)
            } else {
                runningSessions.forEach { session ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "${session.taskName} (${session.taskId})",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "session=${session.sessionId} | started=${formatTimestamp(session.startedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "cycles=${session.totalCycles} | success=${session.successCycles} | failed=${session.failedCycles}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "current=${session.currentCredentialAlias ?: "未标记"} | next=${session.nextCredentialAlias ?: "未标记"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (session.lastErrorCode != null) {
                                Text(
                                    text = "lastError=${session.lastErrorCode}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentRunsCard(recentRuns: List<RecentRunSummary>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "最近运行记录", style = MaterialTheme.typography.titleMedium)
            if (recentRuns.isEmpty()) {
                Text(text = "当前没有任务运行记录。", style = MaterialTheme.typography.bodyMedium)
            } else {
                recentRuns.forEach { run ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "${run.taskName} (${run.taskId})",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "run=${run.runId} | status=${run.status} | trigger=${run.triggerType}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "started=${formatTimestamp(run.startedAt)} | finished=${formatTimestamp(run.finishedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                text = "session=${run.sessionId ?: "无"} | cycle=${run.cycleNo ?: 0} | credential=${run.credentialAlias ?: "未标记"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (run.errorCode != null) {
                                Text(
                                    text = "error=${run.errorCode}${run.message?.let { " | $it" } ?: ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else if (run.message != null) {
                                Text(
                                    text = "message=${run.message}",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ValidationCard(
    packageName: String,
    onPackageNameChange: (String) -> Unit,
    selectorValue: String,
    onSelectorValueChange: (String) -> Unit,
    selectorType: String,
    onSelectorTypeChange: (String) -> Unit,
    actionInFlight: Boolean,
    lastValidationText: String,
    onInspectEnvironment: () -> Unit,
    onRunSmokeCheck: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = "设备验证入口", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = packageName,
                onValueChange = onPackageNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("目标包名") },
                enabled = !actionInFlight,
            )
            OutlinedTextField(
                value = selectorValue,
                onValueChange = onSelectorValueChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("选择器值") },
                enabled = !actionInFlight,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    SelectorType.RESOURCE_ID.name to "ResourceId",
                    SelectorType.TEXT.name to "Text",
                    SelectorType.CONTENT_DESCRIPTION.name to "ContentDesc",
                ).forEach { (value, label) ->
                    FilterChip(
                        selected = selectorType == value,
                        onClick = { onSelectorTypeChange(value) },
                        label = { Text(label) },
                        enabled = !actionInFlight,
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onInspectEnvironment, enabled = !actionInFlight) {
                    Text("检查环境")
                }
                Button(onClick = onRunSmokeCheck, enabled = !actionInFlight) {
                    Text("验证点击链路")
                }
            }
            Text(
                text = lastValidationText,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

internal fun DeviceEnvironmentReport.toDisplayText(): String = buildString {
    appendLine("Root: ${if (rootReady) "ready" else "missing"}")
    appendLine("Accessibility enabled: $accessibilityEnabled")
    appendLine("Accessibility connected: $accessibilityConnected")
    append("Foreground package: ${foregroundPackageName ?: "unknown"}")
}

internal fun formatDeviceValidationResult(result: DeviceValidationResult): String = buildString {
    appendLine(result.environment.toDisplayText())
    if (result.errorCode != null) {
        val statusLabel = when (result.errorCode) {
            DeviceValidationErrorCode.SMOKE_CHECK_EXECUTION_EXCEPTION -> "Smoke check failed"
            else -> "Smoke check blocked"
        }
        append("$statusLabel: ${result.errorCode}${result.message?.let { " - $it" } ?: ""}")
    } else {
        val executionStatus = result.execution?.taskRun?.status
        val statusLabel = when (executionStatus) {
            TaskRunStatus.SUCCESS -> "Smoke check succeeded"
            TaskRunStatus.BLOCKED -> "Smoke check blocked"
            TaskRunStatus.CANCELLED -> "Smoke check cancelled"
            TaskRunStatus.TIMED_OUT -> "Smoke check timed out"
            TaskRunStatus.FAILED -> "Smoke check failed"
            else -> "Smoke check result"
        }
        append("$statusLabel: ${executionStatus ?: "unknown"}")
    }
}

private fun DeviceValidationResult.toDisplayText(): String = formatDeviceValidationResult(this)

private fun formatTimestamp(timestamp: Long?): String {
    if (timestamp == null) {
        return "未计划"
    }
    return Instant.ofEpochMilli(timestamp)
        .atZone(ZoneId.systemDefault())
        .format(dashboardTimestampFormatter)
}

private fun String.toSelectorType(): SelectorType = when (this) {
    SelectorType.RESOURCE_ID.name -> SelectorType.RESOURCE_ID
    SelectorType.CONTENT_DESCRIPTION.name -> SelectorType.CONTENT_DESCRIPTION
    else -> SelectorType.TEXT
}

private fun String.toCredentialSetMembers(): List<CredentialSetMemberInput> = lineSequence()
    .flatMap { line -> line.split(',').asSequence() }
    .map(String::trim)
    .filter(String::isNotBlank)
    .map { token ->
        val parts = token.split('|').map(String::trim)
        require(parts.size == 2) {
            "账号组成员格式无效：$token。请使用 profileId|true 或 profileId|false。"
        }
        val profileId = parts.first()
        require(profileId.isNotBlank()) {
            "账号组成员 profileId 不能为空。"
        }
        val enabled = parts[1].toBooleanFlag()
            ?: throw IllegalArgumentException(
                "账号组成员启停值无效：$token。请使用 true/false。",
            )
        CredentialSetMemberInput(
            profileId = profileId,
            enabled = enabled,
        )
    }
    .toList()

private fun List<com.plearn.appcontrol.appservice.CredentialSetMemberSummary>.toEditorText(): String =
    joinToString(separator = "\n") { item -> "${item.profileId}|${item.itemEnabled}" }

private fun com.plearn.appcontrol.appservice.CredentialSetMemberSummary.toDisplayText(): String = buildString {
    append(alias)
    append("/")
    append(profileId)
    if (!itemEnabled) {
        append(" [member-off]")
    }
    if (!profileEnabled) {
        append(" [profile-off]")
    }
}

private fun String.toBooleanFlag(): Boolean? = when (lowercase()) {
    "true", "enabled", "on", "1" -> true
    "false", "disabled", "off", "0" -> false
    else -> null
}

private fun String.toArtifactsDisplayText(): String? {
    return toDiagnosticArtifactDisplayText()
}

private val dashboardTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
private const val DEFAULT_CREDENTIAL_SET_STRATEGY = "round_robin"
private const val DEFAULT_ENCRYPTION_VERSION = 1

private val defaultTaskImportJson: String = """
        {
            "schemaVersion": "1.0",
            "taskId": "sample-task",
            "name": "Sample Task",
            "enabled": true,
            "targetApp": {
                "packageName": "com.example.target"
            },
            "trigger": {
                "type": "cron",
                "expression": "*/15 * * * *",
                "timezone": "Asia/Shanghai"
            },
            "executionPolicy": {
                "taskTimeoutMs": 60000,
                "maxRetries": 0,
                "retryBackoffMs": 1000,
                "conflictPolicy": "skip",
                "onMissedSchedule": "skip"
            },
            "steps": [
                {
                    "id": "step-start-app",
                    "type": "start_app",
                    "timeoutMs": 5000,
                    "params": {
                        "packageName": "com.example.target"
                    }
                }
            ]
        }
""".trimIndent()
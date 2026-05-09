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
import androidx.compose.ui.unit.dp
import com.plearn.appcontrol.appservice.AppDashboardService
import com.plearn.appcontrol.appservice.AppDashboardSnapshot
import com.plearn.appcontrol.appservice.DeviceEnvironmentReport
import com.plearn.appcontrol.appservice.DeviceValidationResult
import com.plearn.appcontrol.appservice.DeviceValidationService
import com.plearn.appcontrol.appservice.RecentRunSummary
import com.plearn.appcontrol.appservice.RunningSessionSummary
import com.plearn.appcontrol.appservice.TaskDashboardItem
import com.plearn.appcontrol.appservice.TapSmokeCheckRequest
import com.plearn.appcontrol.capability.ElementSelector
import com.plearn.appcontrol.capability.SelectorType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

@Composable
fun AppControlApp(
    deviceValidationService: DeviceValidationService,
    dashboardService: AppDashboardService,
) {
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    var dashboardSnapshot by remember { mutableStateOf<AppDashboardSnapshot?>(null) }
    var dashboardLoading by remember { mutableStateOf(false) }
    var environmentReport by remember { mutableStateOf<DeviceEnvironmentReport?>(null) }
    var validationResult by remember { mutableStateOf<DeviceValidationResult?>(null) }
    var packageName by rememberSaveable { mutableStateOf("com.example.target") }
    var selectorValue by rememberSaveable { mutableStateOf("com.example.target:id/login_button") }
    var selectorType by rememberSaveable { mutableStateOf(SelectorType.RESOURCE_ID.name) }
    var actionInFlight by remember { mutableStateOf(false) }

    fun refreshDashboard() {
        if (dashboardLoading) {
            return
        }
        scope.launch {
            dashboardLoading = true
            try {
                dashboardSnapshot = dashboardService.loadSnapshot()
            } finally {
                dashboardLoading = false
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
                        body = "支持 scheduler 前台服务恢复链、任务列表读侧、最近运行记录概览，以及环境检查与点击链路 smoke 验证。",
                    )
                    StatusCard(
                        title = "下一步",
                        body = "继续补真实任务导入/启停入口、监控详情页和更完整的 Phase 5 稳定性验证。",
                    )
                    DashboardOverviewCard(
                        snapshot = dashboardSnapshot,
                        loading = dashboardLoading,
                        onRefresh = ::refreshDashboard,
                    )
                    TaskListCard(tasks = dashboardSnapshot?.tasks.orEmpty())
                    RunningSessionsCard(runningSessions = dashboardSnapshot?.runningSessions.orEmpty())
                    RecentRunsCard(recentRuns = dashboardSnapshot?.recentRuns.orEmpty())
                    StatusCard(
                        title = "环境检查",
                        body = environmentReport?.toDisplayText() ?: "尚未执行环境检查。",
                    )
                    ValidationCard(
                        packageName = packageName,
                        onPackageNameChange = { packageName = it },
                        selectorValue = selectorValue,
                        onSelectorValueChange = { selectorValue = it },
                        selectorType = selectorType,
                        onSelectorTypeChange = { selectorType = it },
                        actionInFlight = actionInFlight,
                        lastValidationText = validationResult?.toDisplayText() ?: "尚未执行点击链路验证。",
                        onInspectEnvironment = {
                            scope.launch {
                                actionInFlight = true
                                try {
                                    environmentReport = deviceValidationService.inspectEnvironment()
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
private fun StatusCard(title: String, body: String) {
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

@Composable
private fun DashboardOverviewCard(
    snapshot: AppDashboardSnapshot?,
    loading: Boolean,
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
                } else {
                    "任务 ${tasks.size} 个，已启用且 ready ${enabledReadyCount} 个，运行中会话 ${runningCount} 个，最近失败 ${recentFailureCount} 次。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = onRefresh, enabled = !loading) {
                Text(if (loading) "刷新中…" else "刷新概览")
            }
        }
    }
}

@Composable
private fun TaskListCard(tasks: List<TaskDashboardItem>) {
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
                    TaskDashboardRow(task = task)
                }
            }
        }
    }
}

@Composable
private fun TaskDashboardRow(task: TaskDashboardItem) {
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
            Text(text = "${task.name} (${task.taskId})", style = MaterialTheme.typography.titleSmall)
            Text(
                text = "enabled=${task.enabled} | definition=${task.definitionStatus} | trigger=${task.triggerType}",
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
                    text = "lastError=${task.latestRunErrorCode}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (task.runningSession != null) {
                Text(
                    text = "running session=${task.runningSession.sessionId} | current=${task.runningSession.currentCredentialAlias ?: "未标记"} | cycles=${task.runningSession.totalCycles}",
                    style = MaterialTheme.typography.bodySmall,
                )
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
                                    text = "error=${run.errorCode}",
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
private fun ValidationCard(
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

private fun DeviceEnvironmentReport.toDisplayText(): String = buildString {
    appendLine("Root: ${if (rootReady) "ready" else "missing"}")
    appendLine("Accessibility enabled: $accessibilityEnabled")
    appendLine("Accessibility connected: $accessibilityConnected")
    append("Foreground package: ${foregroundPackageName ?: "unknown"}")
}

private fun DeviceValidationResult.toDisplayText(): String = buildString {
    appendLine(environment.toDisplayText())
    if (errorCode != null) {
        append("Smoke check blocked: $errorCode${message?.let { " - $it" } ?: ""}")
    } else {
        append("Smoke check result: ${execution?.taskRun?.status ?: "unknown"}")
    }
}

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

private val dashboardTimestampFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
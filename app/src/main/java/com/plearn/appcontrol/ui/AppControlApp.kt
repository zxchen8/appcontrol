package com.plearn.appcontrol.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.plearn.appcontrol.appservice.DeviceEnvironmentReport
import com.plearn.appcontrol.appservice.DeviceValidationResult
import com.plearn.appcontrol.appservice.DeviceValidationService
import com.plearn.appcontrol.appservice.TapSmokeCheckRequest
import com.plearn.appcontrol.capability.ElementSelector
import com.plearn.appcontrol.capability.SelectorType
import kotlinx.coroutines.launch

@Composable
fun AppControlApp(deviceValidationService: DeviceValidationService) {
    val scope = rememberCoroutineScope()
    var environmentReport by remember { mutableStateOf<DeviceEnvironmentReport?>(null) }
    var validationResult by remember { mutableStateOf<DeviceValidationResult?>(null) }
    var packageName by rememberSaveable { mutableStateOf("com.example.target") }
    var selectorValue by rememberSaveable { mutableStateOf("com.example.target:id/login_button") }
    var selectorType by rememberSaveable { mutableStateOf(SelectorType.RESOURCE_ID.name) }
    var actionInFlight by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Scaffold { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Text(
                        text = "AppControl",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        text = "当前已接入 capability 层、真实 AccessibilityService 与最小 Phase 3 runner-engine。",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    StatusCard(
                        title = "当前状态",
                        body = "支持手动执行 runner 纵切、环境检查、前台包读取和最小点击链路 smoke 验证。",
                    )
                    StatusCard(
                        title = "下一步",
                        body = "继续把 runner 结果接到仓储、调度和真实手动任务入口。",
                    )
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
                                environmentReport = deviceValidationService.inspectEnvironment()
                                actionInFlight = false
                            }
                        },
                        onRunSmokeCheck = {
                            scope.launch {
                                actionInFlight = true
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
                                actionInFlight = false
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

private fun String.toSelectorType(): SelectorType = when (this) {
    SelectorType.RESOURCE_ID.name -> SelectorType.RESOURCE_ID
    SelectorType.CONTENT_DESCRIPTION.name -> SelectorType.CONTENT_DESCRIPTION
    else -> SelectorType.TEXT
}
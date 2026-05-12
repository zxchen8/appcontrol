package com.plearn.appcontrol.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.plearn.appcontrol.appservice.DeviceEnvironmentReport
import com.plearn.appcontrol.capability.SelectorType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceValidationUiSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun shouldRenderEnvironmentDetailsAfterInspectEnvironmentClick() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val scrollState = rememberScrollState()
                    val defaultEnvironmentText = "尚未执行环境检查。"
                    var environmentText by remember { mutableStateOf(defaultEnvironmentText) }
                    val sampleEnvironment = DeviceEnvironmentReport(
                        rootReady = true,
                        accessibilityEnabled = true,
                        accessibilityConnected = false,
                        foregroundPackageName = "com.example.target",
                        notificationsEnabled = true,
                        targetPackageName = "com.example.target",
                        targetPackageInstalled = true,
                        deviceTimezoneId = "Asia/Shanghai",
                        sampleTimezoneId = "Asia/Shanghai",
                        sampleTimezoneAligned = true,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        StatusCard(
                            title = "环境检查",
                            body = environmentText,
                        )
                        ValidationCard(
                            packageName = "com.example.target",
                            onPackageNameChange = {},
                            selectorValue = "com.example.target:id/login_button",
                            onSelectorValueChange = {},
                            selectorType = SelectorType.RESOURCE_ID.name,
                            onSelectorTypeChange = {},
                            actionInFlight = false,
                            lastValidationText = "尚未执行点击链路验证。",
                            onInspectEnvironment = {
                                environmentText = sampleEnvironment.toDisplayText()
                            },
                            onRunSmokeCheck = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("尚未执行环境检查。").assertIsDisplayed()
        composeRule.onNodeWithText("检查环境").performScrollTo().performClick()

        composeRule.onNodeWithText("Root: ready", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Accessibility enabled: true", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Accessibility connected: false", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Foreground package: com.example.target", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Notifications enabled: true", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Target package: com.example.target", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Target package installed: true", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Device timezone: Asia/Shanghai", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Sample timezone: Asia/Shanghai", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Timezone aligned: true", substring = true).assertIsDisplayed()
    }

    @Test
    fun shouldUseEditedPackageNameWhenInspectEnvironmentIsClicked() {
        composeRule.setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val scrollState = rememberScrollState()
                    var packageName by remember { mutableStateOf("com.example.target") }
                    var environmentText by remember { mutableStateOf("尚未执行环境检查。") }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        StatusCard(
                            title = "环境检查",
                            body = environmentText,
                        )
                        ValidationCard(
                            packageName = packageName,
                            onPackageNameChange = { packageName = it },
                            selectorValue = "com.example.target:id/login_button",
                            onSelectorValueChange = {},
                            selectorType = SelectorType.RESOURCE_ID.name,
                            onSelectorTypeChange = {},
                            actionInFlight = false,
                            lastValidationText = "尚未执行点击链路验证。",
                            onInspectEnvironment = {
                                environmentText = "Inspect requested: $packageName"
                            },
                            onRunSmokeCheck = {},
                        )
                    }
                }
            }
        }

        composeRule.onNodeWithText("com.example.target").performTextReplacement("com.example.override")
        composeRule.onNodeWithText("检查环境").performScrollTo().performClick()

        composeRule.onNodeWithText("Inspect requested: com.example.override").assertIsDisplayed()
    }
}
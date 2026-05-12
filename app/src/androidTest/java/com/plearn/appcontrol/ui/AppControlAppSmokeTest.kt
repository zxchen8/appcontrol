package com.plearn.appcontrol.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.plearn.appcontrol.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppControlAppSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun shouldRenderDashboardSummaryCardsOnLaunch() {
        composeRule.onNodeWithText("AppControl").assertIsDisplayed()
        composeRule.onNodeWithText("当前状态").assertIsDisplayed()
        composeRule.onNodeWithText("下一步").assertIsDisplayed()
    }

    @Test
    fun shouldExposeTaskAndDeviceValidationEntryPoints() {
        composeRule.onNodeWithText("导入或更新任务").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("检查环境").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("验证点击链路").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun shouldImportDefaultTaskAndShowItInTaskList() {
        val importedTask = importUniqueTask()

        composeRule.onNodeWithTag("task-row-${importedTask.taskId}").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("task-state-${importedTask.taskId}-enabled").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("task-toggle-${importedTask.taskId}-disable").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun shouldToggleImportedTaskDisabledAndEnabledAgain() {
        val importedTask = importUniqueTask()

        composeRule.onNodeWithTag("task-toggle-${importedTask.taskId}-disable")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onAllNodesWithTag("task-state-${importedTask.taskId}-disabled")
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }

        composeRule.onNodeWithTag("task-state-${importedTask.taskId}-disabled")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("task-toggle-${importedTask.taskId}-enable")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("task-toggle-${importedTask.taskId}-enable").assertIsEnabled()
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithTag("task-toggle-${importedTask.taskId}-enable")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onAllNodesWithTag("task-state-${importedTask.taskId}-enabled")
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("task-toggle-${importedTask.taskId}-disable").assertIsEnabled()
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithTag("task-state-${importedTask.taskId}-enabled")
            .performScrollTo()
            .assertIsDisplayed()
        composeRule.onNodeWithTag("task-toggle-${importedTask.taskId}-disable")
            .performScrollTo()
            .assertIsDisplayed()
    }

    private fun importUniqueTask(): ImportedTask {
        val uniqueSuffix = System.currentTimeMillis()
        val taskId = "sample-task-$uniqueSuffix"
        val taskName = "Sample Task $uniqueSuffix"

        composeRule.onNodeWithTag("task-editor-json").performTextReplacement(
            uniqueTaskJson(
                taskId = taskId,
                taskName = taskName,
            ),
        )
        composeRule.onNodeWithText("导入或更新任务").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onAllNodesWithTag("task-row-$taskId")
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("task-toggle-$taskId-disable").assertIsEnabled()
                true
            }.getOrDefault(false)
        }

        return ImportedTask(
            taskId = taskId,
            taskName = taskName,
        )
    }

    private data class ImportedTask(
        val taskId: String,
        val taskName: String,
    )

    private fun uniqueTaskJson(taskId: String, taskName: String): String =
        """
        {
            "schemaVersion": "1.0",
            "taskId": "$taskId",
            "name": "$taskName",
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
}
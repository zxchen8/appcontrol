package com.plearn.appcontrol.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
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
import org.junit.Assert.assertEquals
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

    @Test
    fun shouldLoadImportedTaskJsonBackIntoEditor() {
        val importedTask = importUniqueTask()
        val placeholderJson = """
            {
                "schemaVersion": "1.0",
                "taskId": "placeholder-task",
                "name": "Placeholder Task",
                "enabled": false,
                "targetApp": {
                    "packageName": "com.example.placeholder"
                },
                "trigger": {
                    "type": "cron",
                    "expression": "*/30 * * * *",
                    "timezone": "Asia/Shanghai"
                },
                "executionPolicy": {
                    "taskTimeoutMs": 30000,
                    "maxRetries": 0,
                    "retryBackoffMs": 1000,
                    "conflictPolicy": "skip",
                    "onMissedSchedule": "skip"
                },
                "steps": []
            }
        """.trimIndent()

        composeRule.onNodeWithTag("task-editor-json").performTextReplacement(placeholderJson)
        composeRule.onNodeWithTag("task-editor-json").assertTextContains("placeholder-task", substring = true)

        composeRule.onNodeWithTag("task-load-json-${importedTask.taskId}")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            editorJson() == importedTask.rawJson
        }

        assertEquals(importedTask.rawJson, editorJson())
    }

    @Test
    fun shouldOpenImportedTaskDetailAndShowScheduleSummary() {
        val importedTask = importUniqueTask()

        openTaskDetail(importedTask)

        composeRule.onNodeWithTag("task-detail-definition-${importedTask.taskId}")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains(importedTask.taskName, substring = true)

        composeRule.onNodeWithTag("task-detail-schedule-${importedTask.taskId}")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("standby=true", substring = true)
    }

    @Test
    fun shouldRefreshTaskDetailScheduleSummaryAfterDisablingSelectedTask() {
        val importedTask = importUniqueTask()

        openTaskDetail(importedTask)

        composeRule.onNodeWithTag("task-toggle-${importedTask.taskId}-disable")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onNodeWithTag("task-detail-schedule-${importedTask.taskId}")
                    .assertTextContains("standby=false", substring = true)
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithTag("task-detail-definition-${importedTask.taskId}")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains(importedTask.taskName, substring = true)
        composeRule.onNodeWithTag("task-detail-schedule-${importedTask.taskId}")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("standby=false", substring = true)
    }

    @Test
    fun shouldShowRecentRunAndStepRecordsAfterManualRun() {
        val importedTask = importUniqueTask()

        openTaskDetail(importedTask)

        composeRule.onNodeWithText("当前任务还没有运行记录。")
            .performScrollTo()
            .assertIsDisplayed()

        composeRule.onNodeWithTag("task-run-now-${importedTask.taskId}")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onAllNodesWithTag("task-detail-selected-run-${importedTask.taskId}")
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onAllNodesWithTag("task-detail-step-${importedTask.taskId}-step-start-app")
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }

        composeRule.onNodeWithTag("task-detail-selected-run-${importedTask.taskId}")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("trigger=manual", substring = true)

        composeRule.onNodeWithTag("task-detail-step-${importedTask.taskId}-step-start-app")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun shouldRefreshTaskLatestSummaryAfterManualRun() {
        val importedTask = importUniqueTask()

        openTaskDetail(importedTask)

        composeRule.onNodeWithTag("task-latest-${importedTask.taskId}")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("latest=无", substring = true)

        runTaskNowAndWaitForDetail(importedTask)

        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithTag("task-latest-${importedTask.taskId}")
                    .assertTextContains("(manual)", substring = true)
                true
            }.getOrDefault(false)
        }

        composeRule.onNodeWithTag("task-latest-${importedTask.taskId}")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains("(manual)", substring = true)
    }

    private fun importUniqueTask(): ImportedTask {
        val uniqueSuffix = System.currentTimeMillis()
        val taskId = "sample-task-$uniqueSuffix"
        val taskName = "Sample Task $uniqueSuffix"
        val rawJson = uniqueTaskJson(
            taskId = taskId,
            taskName = taskName,
        )

        composeRule.onNodeWithTag("task-editor-json").performTextReplacement(rawJson)
        composeRule.onNodeWithText("导入或更新任务").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onAllNodesWithTag("task-row-$taskId")
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }

        composeRule.waitUntil(timeoutMillis = 15_000) {
            runCatching {
                composeRule.onNodeWithTag("task-toggle-$taskId-disable").assertIsEnabled()
                true
            }.getOrDefault(false)
        }

        return ImportedTask(
            taskId = taskId,
            taskName = taskName,
            rawJson = rawJson,
        )
    }

    private data class ImportedTask(
        val taskId: String,
        val taskName: String,
        val rawJson: String,
    )

    private fun openTaskDetail(importedTask: ImportedTask) {
        composeRule.onNodeWithTag("task-detail-${importedTask.taskId}")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 10_000) {
            runCatching {
                composeRule.onAllNodesWithTag("task-detail-definition-${importedTask.taskId}")
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun runTaskNowAndWaitForDetail(importedTask: ImportedTask) {
        composeRule.onNodeWithTag("task-run-now-${importedTask.taskId}")
            .performScrollTo()
            .performClick()

        composeRule.waitUntil(timeoutMillis = 30_000) {
            runCatching {
                composeRule.onAllNodesWithTag("task-detail-selected-run-${importedTask.taskId}")
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }

        composeRule.waitUntil(timeoutMillis = 30_000) {
            runCatching {
                composeRule.onAllNodesWithTag("task-detail-step-${importedTask.taskId}-step-start-app")
                    .fetchSemanticsNodes().isNotEmpty()
            }.getOrDefault(false)
        }

        composeRule.waitUntil(timeoutMillis = 30_000) {
            runCatching {
                composeRule.onNodeWithTag("task-run-now-${importedTask.taskId}").assertIsEnabled()
                true
            }.getOrDefault(false)
        }
    }

    private fun editorJson(): String = composeRule.onNodeWithTag("task-editor-json")
        .fetchSemanticsNode()
        .config[SemanticsProperties.EditableText]
        .text

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
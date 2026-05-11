package com.plearn.appcontrol.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
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
}
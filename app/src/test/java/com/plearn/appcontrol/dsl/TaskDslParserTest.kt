package com.plearn.appcontrol.dsl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskDslParserTest {
    private val parser = TaskDslParser()

    @Test
    fun shouldParseMinimalCronTaskAndApplyStepDefaults() {
        val result = parser.parse(validCronTaskJson())

        assertEquals(DefinitionStatus.READY, result.definitionStatus)
        assertTrue(result.errors.isEmpty())
        val task = requireNotNull(result.task)
        assertEquals("daily-login-check", task.taskId)
        assertEquals(StepRetryPolicy(), task.steps.first().retry)
        assertEquals(StepFailurePolicy.STOP_TASK, task.steps.first().onFailure)
        assertFalse(task.steps.first().clearsSensitiveContext)
    }

    @Test
    fun shouldParseContinuousTaskWithDiagnosticsFlags() {
        val result = parser.parse(validContinuousTaskJson())

        assertEquals(DefinitionStatus.READY, result.definitionStatus)
        val task = requireNotNull(result.task)
        assertTrue(task.trigger is TaskTrigger.Continuous)
        assertFalse(task.diagnostics.captureScreenshotOnFailure)
        assertFalse(task.diagnostics.captureScreenshotOnStepFailure)
    }

      @Test
      fun shouldPreserveOptionalTopLevelFieldsWithoutDroppingThem() {
        val result = parser.parse(continuousTaskWithOptionalFields())

        assertEquals(DefinitionStatus.READY, result.definitionStatus)
        val task = requireNotNull(result.task)
        assertNotNull(task.accountRotation)
        assertNotNull(task.variables)
        assertEquals(1, task.preconditions.size)
        assertEquals(listOf("login", "smoke"), task.tags)
      }

    @Test
    fun shouldRejectTaskWhenRequiredTopLevelFieldStepsIsMissing() {
        val result = parser.parse(taskWithoutSteps())

        assertEquals(DefinitionStatus.INVALID, result.definitionStatus)
        assertError(result, "steps", DslValidationCode.MISSING_REQUIRED_FIELD)
    }

    @Test
    fun shouldRejectTaskWhenSchemaVersionIsUnsupported() {
        val result = parser.parse(validCronTaskJson().replace("\"schemaVersion\": \"1.0\"", "\"schemaVersion\": \"2.0\""))

        assertEquals(DefinitionStatus.INVALID, result.definitionStatus)
        assertError(result, "schemaVersion", DslValidationCode.UNSUPPORTED_SCHEMA_VERSION)
    }

    @Test
    fun shouldRejectTaskWhenTriggerTypeIsManual() {
        val result = parser.parse(taskWithManualTrigger())

        assertEquals(DefinitionStatus.INVALID, result.definitionStatus)
        assertError(result, "trigger.type", DslValidationCode.INVALID_ENUM)
    }

    @Test
    fun shouldRejectCronTriggerWhenExpressionIsNotFivePart() {
        val result = parser.parse(validCronTaskJson().replace("\"*/30 * * * *\"", "\"* * * * * *\""))

        assertEquals(DefinitionStatus.INVALID, result.definitionStatus)
        assertError(result, "trigger.expression", DslValidationCode.INVALID_FORMAT)
    }

    @Test
    fun shouldRejectExecutionPolicyWhenMaxRetriesIsNegative() {
        val result = parser.parse(validCronTaskJson().replace("\"maxRetries\": 1", "\"maxRetries\": -1"))

        assertEquals(DefinitionStatus.INVALID, result.definitionStatus)
        assertError(result, "executionPolicy.maxRetries", DslValidationCode.INVALID_VALUE)
    }

    @Test
    fun shouldRejectInputTextStepWhenTextAndTextRefAreBothProvided() {
        val result = parser.parse(taskWithConflictingInputTextFields())

        assertEquals(DefinitionStatus.INVALID, result.definitionStatus)
        assertError(result, "steps[0].params", DslValidationCode.CONFLICTING_FIELDS)
    }

    @Test
    fun shouldRejectTaskWhenStepIdsAreDuplicated() {
        val result = parser.parse(taskWithDuplicateStepIds())

        assertEquals(DefinitionStatus.INVALID, result.definitionStatus)
        assertError(result, "steps[1].id", DslValidationCode.DUPLICATE_ID)
    }

    @Test
    fun shouldRejectSwipeStepWhenRequiredParamsAreMissing() {
      val result = parser.parse(taskWithInvalidSwipeStep())

      assertEquals(DefinitionStatus.INVALID, result.definitionStatus)
      assertError(result, "steps[0].params.from", DslValidationCode.MISSING_REQUIRED_FIELD)
    }

    @Test
    fun shouldRejectWaitElementStepWhenStateIsMissing() {
      val result = parser.parse(taskWithInvalidWaitElementStep())

      assertEquals(DefinitionStatus.INVALID, result.definitionStatus)
      assertError(result, "steps[0].params.state", DslValidationCode.MISSING_REQUIRED_FIELD)
    }

    private fun assertError(
        result: TaskDefinitionParseResult,
        path: String,
        code: DslValidationCode,
    ) {
        assertTrue(
            "Expected error $code at path $path, but found ${result.errors}",
            result.errors.any { it.path == path && it.code == code },
        )
    }

    private fun validCronTaskJson(): String =
        """
        {
          "schemaVersion": "1.0",
          "taskId": "daily-login-check",
          "name": "每日登录校验",
          "enabled": true,
          "targetApp": {
            "packageName": "com.example.target"
          },
          "trigger": {
            "type": "cron",
            "expression": "*/30 * * * *",
            "timezone": "Asia/Shanghai"
          },
          "executionPolicy": {
            "taskTimeoutMs": 300000,
            "maxRetries": 1,
            "retryBackoffMs": 5000,
            "conflictPolicy": "skip",
            "onMissedSchedule": "skip"
          },
          "steps": [
            {
              "id": "step-start",
              "type": "start_app",
              "timeoutMs": 15000,
              "params": {
                "packageName": "com.example.target"
              }
            }
          ],
          "diagnostics": {
            "captureScreenshotOnFailure": true,
            "captureScreenshotOnStepFailure": true
          }
        }
        """.trimIndent()

    private fun validContinuousTaskJson(): String =
        """
        {
          "schemaVersion": "1.0",
          "taskId": "loop-login-check",
          "name": "连续登录校验",
          "enabled": true,
          "targetApp": {
            "packageName": "com.example.target"
          },
          "trigger": {
            "type": "continuous",
            "cooldownMs": 5000,
            "maxCycles": 3,
            "maxDurationMs": 600000
          },
          "executionPolicy": {
            "taskTimeoutMs": 300000,
            "maxRetries": 0,
            "retryBackoffMs": 1000,
            "conflictPolicy": "skip",
            "onMissedSchedule": "skip"
          },
          "steps": [
            {
              "id": "step-start",
              "type": "start_app",
              "timeoutMs": 15000,
              "params": {
                "packageName": "com.example.target"
              }
            }
          ],
          "diagnostics": {
            "captureScreenshotOnFailure": false,
            "captureScreenshotOnStepFailure": false
          }
        }
        """.trimIndent()

    private fun continuousTaskWithOptionalFields(): String =
        """
        {
          "schemaVersion": "1.0",
          "taskId": "loop-login-check",
          "name": "连续登录校验",
          "enabled": true,
          "targetApp": {
            "packageName": "com.example.target"
          },
          "trigger": {
            "type": "continuous",
            "cooldownMs": 5000,
            "maxCycles": 3,
            "maxDurationMs": 600000
          },
          "accountRotation": {
            "credentialSetId": "smoke-set-a",
            "strategy": "round_robin",
            "persistCursor": true,
            "onCycleFailure": "continue_next"
          },
          "executionPolicy": {
            "taskTimeoutMs": 300000,
            "maxRetries": 0,
            "retryBackoffMs": 1000,
            "conflictPolicy": "skip",
            "onMissedSchedule": "skip"
          },
          "preconditions": [
            {
              "type": "root_ready"
            }
          ],
          "variables": {
            "ACCOUNT_USERNAME": {
              "source": "active_credential",
              "field": "username"
            }
          },
          "steps": [
            {
              "id": "step-start",
              "type": "start_app",
              "timeoutMs": 15000,
              "params": {
                "packageName": "com.example.target"
              }
            }
          ],
          "tags": ["login", "smoke"]
        }
        """.trimIndent()

    private fun taskWithConflictingInputTextFields(): String =
        """
        {
          "schemaVersion": "1.0",
          "taskId": "daily-login-check",
          "name": "每日登录校验",
          "enabled": true,
          "targetApp": {
            "packageName": "com.example.target"
          },
          "trigger": {
            "type": "cron",
            "expression": "*/30 * * * *",
            "timezone": "Asia/Shanghai"
          },
          "executionPolicy": {
            "taskTimeoutMs": 300000,
            "maxRetries": 1,
            "retryBackoffMs": 5000,
            "conflictPolicy": "skip",
            "onMissedSchedule": "skip"
          },
          "steps": [
            {
              "id": "step-input-user",
              "type": "input_text",
              "timeoutMs": 10000,
              "params": {
                "selector": {
                  "by": "resourceId",
                  "value": "com.example.target:id/phone"
                },
                "text": "alice",
                "textRef": "ACCOUNT_USERNAME"
              }
            }
          ]
        }
        """.trimIndent()

    private fun taskWithoutSteps(): String =
        """
        {
          "schemaVersion": "1.0",
          "taskId": "daily-login-check",
          "name": "每日登录校验",
          "enabled": true,
          "targetApp": {
            "packageName": "com.example.target"
          },
          "trigger": {
            "type": "cron",
            "expression": "*/30 * * * *",
            "timezone": "Asia/Shanghai"
          },
          "executionPolicy": {
            "taskTimeoutMs": 300000,
            "maxRetries": 1,
            "retryBackoffMs": 5000,
            "conflictPolicy": "skip",
            "onMissedSchedule": "skip"
          }
        }
        """.trimIndent()

    private fun taskWithManualTrigger(): String =
        """
        {
          "schemaVersion": "1.0",
          "taskId": "daily-login-check",
          "name": "每日登录校验",
          "enabled": true,
          "targetApp": {
            "packageName": "com.example.target"
          },
          "trigger": {
            "type": "manual"
          },
          "executionPolicy": {
            "taskTimeoutMs": 300000,
            "maxRetries": 1,
            "retryBackoffMs": 5000,
            "conflictPolicy": "skip",
            "onMissedSchedule": "skip"
          },
          "steps": [
            {
              "id": "step-start",
              "type": "start_app",
              "timeoutMs": 15000,
              "params": {
                "packageName": "com.example.target"
              }
            }
          ]
        }
        """.trimIndent()

    private fun taskWithDuplicateStepIds(): String =
        """
        {
          "schemaVersion": "1.0",
          "taskId": "daily-login-check",
          "name": "每日登录校验",
          "enabled": true,
          "targetApp": {
            "packageName": "com.example.target"
          },
          "trigger": {
            "type": "cron",
            "expression": "*/30 * * * *",
            "timezone": "Asia/Shanghai"
          },
          "executionPolicy": {
            "taskTimeoutMs": 300000,
            "maxRetries": 1,
            "retryBackoffMs": 5000,
            "conflictPolicy": "skip",
            "onMissedSchedule": "skip"
          },
          "steps": [
            {
              "id": "dup-step",
              "type": "start_app",
              "timeoutMs": 15000,
              "params": {
                "packageName": "com.example.target"
              }
            },
            {
              "id": "dup-step",
              "type": "stop_app",
              "timeoutMs": 15000,
              "params": {
                "packageName": "com.example.target"
              }
            }
          ]
        }
        """.trimIndent()

    private fun taskWithInvalidSwipeStep(): String =
        """
        {
          "schemaVersion": "1.0",
          "taskId": "daily-login-check",
          "name": "每日登录校验",
          "enabled": true,
          "targetApp": {
            "packageName": "com.example.target"
          },
          "trigger": {
            "type": "cron",
            "expression": "*/30 * * * *",
            "timezone": "Asia/Shanghai"
          },
          "executionPolicy": {
            "taskTimeoutMs": 300000,
            "maxRetries": 1,
            "retryBackoffMs": 5000,
            "conflictPolicy": "skip",
            "onMissedSchedule": "skip"
          },
          "steps": [
            {
              "id": "step-swipe",
              "type": "swipe",
              "timeoutMs": 15000,
              "params": {
                "to": {
                  "x": 500,
                  "y": 900
                },
                "durationMs": 300
              }
            }
          ]
        }
        """.trimIndent()

    private fun taskWithInvalidWaitElementStep(): String =
        """
        {
          "schemaVersion": "1.0",
          "taskId": "daily-login-check",
          "name": "每日登录校验",
          "enabled": true,
          "targetApp": {
            "packageName": "com.example.target"
          },
          "trigger": {
            "type": "cron",
            "expression": "*/30 * * * *",
            "timezone": "Asia/Shanghai"
          },
          "executionPolicy": {
            "taskTimeoutMs": 300000,
            "maxRetries": 1,
            "retryBackoffMs": 5000,
            "conflictPolicy": "skip",
            "onMissedSchedule": "skip"
          },
          "steps": [
            {
              "id": "step-wait",
              "type": "wait_element",
              "timeoutMs": 15000,
              "params": {
                "selector": {
                  "by": "resourceId",
                  "value": "com.example.target:id/login"
                }
              }
            }
          ]
        }
        """.trimIndent()
}
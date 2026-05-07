package com.plearn.appcontrol.scheduler

import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

class CronScheduleCalculator {
    fun nextTriggerAt(expression: String, timezone: String, afterEpochMs: Long): Long {
        val parts = expression.trim().split(Regex("\\s+"))
        require(parts.size == 5) { "Cron expression must have exactly five parts." }

        val zoneId = ZoneId.of(timezone)
        var candidate = Instant.ofEpochMilli(afterEpochMs)
            .atZone(zoneId)
            .truncatedTo(ChronoUnit.MINUTES)
            .plusMinutes(1)

        repeat(MAX_SEARCH_MINUTES) {
            if (matches(parts, candidate)) {
                return candidate.toInstant().toEpochMilli()
            }
            candidate = candidate.plusMinutes(1)
        }

        error("Unable to compute next trigger for cron expression: $expression")
    }

    private fun matches(parts: List<String>, candidate: ZonedDateTime): Boolean {
        val minuteMatches = matchesField(parts[0], candidate.minute, 0, 59)
        val hourMatches = matchesField(parts[1], candidate.hour, 0, 23)
        val dayOfMonthMatches = matchesField(parts[2], candidate.dayOfMonth, 1, 31)
        val monthMatches = matchesField(parts[3], candidate.monthValue, 1, 12)
        val dayOfWeek = candidate.dayOfWeek.value % 7
        val dayOfWeekMatches = matchesField(parts[4], dayOfWeek, 0, 6)

        return minuteMatches && hourMatches && dayOfMonthMatches && monthMatches && dayOfWeekMatches
    }

    private fun matchesField(expression: String, value: Int, min: Int, max: Int): Boolean {
        if (expression == "*") {
            return true
        }

        return expression.split(',').any { segment ->
            when {
                segment.startsWith("*/") -> {
                    val step = segment.removePrefix("*/").toIntOrNull()
                    step != null && step > 0 && (value - min) % step == 0
                }

                segment.contains('-') -> {
                    val bounds = segment.split('-')
                    val start = bounds.getOrNull(0)?.toIntOrNull()
                    val end = bounds.getOrNull(1)?.toIntOrNull()
                    start != null && end != null && value in start..end
                }

                else -> segment.toIntOrNull() == value
            }
        }.also {
            require(value in min..max) { "Value $value is outside supported cron field bounds." }
        }
    }

    private companion object {
        const val MAX_SEARCH_MINUTES = 366 * 24 * 60
    }
}
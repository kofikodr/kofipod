// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.scheduler

import com.kofikodr.kofipod.background.SchedulerRun
import com.kofikodr.kofipod.background.SchedulerRunKind
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** Number of trailing calendar days the "Last 7 days" chart visualises. */
const val SCHEDULER_CHART_DAYS = 7

/**
 * One day's worth of scheduled work, as the chart sees it. [episodeInserted] is the
 * sum of `inserted` across every episode-check run that landed in this day-bucket,
 * or `null` if no episode-check ran. [hasBackup] is presence-only — multiple backups
 * on the same day collapse to a single marker.
 */
data class DayBucket(
    val date: LocalDate,
    val episodeInserted: Int?,
    val hasBackup: Boolean,
)

/**
 * Group [runs] into exactly [SCHEDULER_CHART_DAYS] calendar-day buckets ending at
 * [today], evaluated in [zone]. Returned list is oldest-first, so [today] is last —
 * matches the chart's left-to-right time axis. Runs outside the window are dropped.
 *
 * Pure function; takes [today] and [zone] as parameters so tests can pin them.
 */
fun bucketRunsByLocalDay(
    runs: List<SchedulerRun>,
    today: LocalDate,
    zone: TimeZone,
): List<DayBucket> {
    val windowStart = today.minus(SCHEDULER_CHART_DAYS - 1, DateTimeUnit.DAY)
    val episodeSum = HashMap<LocalDate, Int>(SCHEDULER_CHART_DAYS)
    val backupDays = HashSet<LocalDate>(SCHEDULER_CHART_DAYS)
    for (run in runs) {
        val day = Instant.fromEpochMilliseconds(run.at).toLocalDateTime(zone).date
        if (day < windowStart || day > today) continue
        when (run.runKind) {
            SchedulerRunKind.EpisodeCheck ->
                episodeSum[day] = (episodeSum[day] ?: 0) + run.inserted
            SchedulerRunKind.Backup ->
                backupDays += day
        }
    }
    return List(SCHEDULER_CHART_DAYS) { i ->
        val date = windowStart.plus(i, DateTimeUnit.DAY)
        DayBucket(
            date = date,
            episodeInserted = episodeSum[date],
            hasBackup = date in backupDays,
        )
    }
}

/**
 * Single-letter weekday label for a real [LocalDate]. ASCII-only to match the rest
 * of the Scheduler screen's typography; not localised (Scheduler copy is English-only
 * across the codebase). Tuesday and Thursday both render as `"T"`, and Saturday and
 * Sunday both as `"S"` — standard sparkline-axis convention, accepted ambiguity.
 */
fun weekdayLetter(date: LocalDate): String =
    when (date.dayOfWeek) {
        DayOfWeek.MONDAY -> "M"
        DayOfWeek.TUESDAY -> "T"
        DayOfWeek.WEDNESDAY -> "W"
        DayOfWeek.THURSDAY -> "T"
        DayOfWeek.FRIDAY -> "F"
        DayOfWeek.SATURDAY -> "S"
        DayOfWeek.SUNDAY -> "S"
    }

// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.scheduler

import com.kofikodr.kofipod.background.SchedulerRun
import com.kofikodr.kofipod.background.SchedulerRunKind
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the bucketing helper that feeds the Scheduler "Last 7 days" chart. Tests pin
 * `today` + `zone` so the result is deterministic; bucketing always uses UTC here
 * because a fixed zone keeps "what day was this millis?" trivial to reason about and
 * the helper's only zone dependency is the conversion call itself.
 */
class SchedulerChartBucketingTest {
    private val today = LocalDate(2026, 5, 12)
    private val zone = TimeZone.UTC

    @Test
    fun emptyRuns_produceSevenEmptyBucketsEndingToday() {
        val buckets = bucketRunsByLocalDay(emptyList(), today, zone)

        assertEquals(7, buckets.size)
        assertEquals(LocalDate(2026, 5, 6), buckets.first().date)
        assertEquals(today, buckets.last().date)
        assertTrue(buckets.all { it.episodeInserted == null && !it.hasBackup })
    }

    @Test
    fun sameDayEpisodeAndBackup_populateBothFieldsInOneBucket() {
        val runs =
            listOf(
                episodeCheck(at = utcMs(2026, 5, 12, 8, 0), inserted = 4),
                backup(at = utcMs(2026, 5, 12, 9, 0)),
            )

        val buckets = bucketRunsByLocalDay(runs, today, zone)
        val todayBucket = buckets.last()

        assertEquals(today, todayBucket.date)
        assertEquals(4, todayBucket.episodeInserted)
        assertTrue(todayBucket.hasBackup)
    }

    @Test
    fun multipleEpisodeChecksSameDay_sumInserted() {
        val runs =
            listOf(
                episodeCheck(at = utcMs(2026, 5, 11, 6, 0), inserted = 3),
                episodeCheck(at = utcMs(2026, 5, 11, 18, 0), inserted = 2),
            )

        val buckets = bucketRunsByLocalDay(runs, today, zone)
        val yesterday = buckets[5]

        assertEquals(LocalDate(2026, 5, 11), yesterday.date)
        assertEquals(5, yesterday.episodeInserted)
        assertFalse(yesterday.hasBackup)
    }

    @Test
    fun multipleBackupsSameDay_collapseToSinglePresenceFlag() {
        val runs =
            listOf(
                backup(at = utcMs(2026, 5, 10, 1, 0)),
                backup(at = utcMs(2026, 5, 10, 12, 0)),
                backup(at = utcMs(2026, 5, 10, 23, 0)),
            )

        val buckets = bucketRunsByLocalDay(runs, today, zone)
        val twoDaysAgo = buckets[4]

        assertEquals(LocalDate(2026, 5, 10), twoDaysAgo.date)
        assertNull(twoDaysAgo.episodeInserted)
        assertTrue(twoDaysAgo.hasBackup)
    }

    @Test
    fun runExactlyOnWindowStart_isIncludedInOldestBucket() {
        // Window for today=2026-05-12 is [2026-05-06 .. 2026-05-12] inclusive. A run
        // timestamped at 2026-05-06T00:00:00Z (the exact window-start boundary) must
        // land in buckets[0]. Pinned in isolation so an off-by-one that drops
        // `windowStart` shows up as a localised failure here rather than as a
        // dropped-total in `runOlderThanWindow_isDropped`.
        val runs = listOf(episodeCheck(at = utcMs(2026, 5, 6, 0, 0), inserted = 1))

        val buckets = bucketRunsByLocalDay(runs, today, zone)

        assertEquals(LocalDate(2026, 5, 6), buckets[0].date)
        assertEquals(1, buckets[0].episodeInserted)
    }

    @Test
    fun episodeCheckWithZeroInserted_producesZeroNotNull() {
        // The chart visually distinguishes "no check ran" (null → muted track stub)
        // from "check ran but found nothing" (0 → mini-bar at the floor height).
        // Pin the boundary so a future refactor that drops `inserted == 0` rows
        // during bucketing surfaces immediately.
        val runs = listOf(episodeCheck(at = utcMs(2026, 5, 12, 6, 0), inserted = 0))

        val buckets = bucketRunsByLocalDay(runs, today, zone)

        assertEquals(0, buckets.last().episodeInserted)
    }

    @Test
    fun runOlderThanWindow_isDropped() {
        // Window for today=2026-05-12 is [2026-05-06 .. 2026-05-12]. A run from 2026-05-05
        // must NOT appear in any bucket.
        val runs =
            listOf(
                episodeCheck(at = utcMs(2026, 5, 5, 12, 0), inserted = 99),
                episodeCheck(at = utcMs(2026, 5, 6, 12, 0), inserted = 1),
            )

        val buckets = bucketRunsByLocalDay(runs, today, zone)

        assertEquals(1, buckets[0].episodeInserted)
        // Total inserted across all buckets equals the one survivor.
        val totalInserted = buckets.sumOf { it.episodeInserted ?: 0 }
        assertEquals(1, totalInserted)
    }

    @Test
    fun futureRun_isDropped() {
        val runs =
            listOf(
                episodeCheck(at = utcMs(2026, 5, 13, 0, 0), inserted = 7),
            )

        val buckets = bucketRunsByLocalDay(runs, today, zone)

        assertTrue(buckets.all { it.episodeInserted == null && !it.hasBackup })
    }

    @Test
    fun bucketingHonoursZone_runJustBeforeUtcMidnightStraddlesDayInOtherZone() {
        // Run at 2026-05-12T23:30Z is still "today" in UTC, but "tomorrow" in UTC+2.
        // Bucketing in UTC+2 with today=2026-05-13 must place the run in today's bucket.
        val plus2 = TimeZone.of("UTC+02:00")
        val runs = listOf(episodeCheck(at = utcMs(2026, 5, 12, 23, 30), inserted = 1))

        val buckets = bucketRunsByLocalDay(runs, LocalDate(2026, 5, 13), plus2)

        assertEquals(1, buckets.last().episodeInserted)
    }

    @Test
    fun bucketingHonoursZone_runJustAfterUtcMidnightBacksToPreviousDayInNegativeOffsetZone() {
        // Symmetric to the UTC+2 test. Run at 2026-05-12T03:00Z is "today" in UTC,
        // but "yesterday" in UTC-5 (2026-05-11T22:00 local). Bucketing in UTC-5
        // with today=2026-05-12 must place the run in the SECOND-TO-LAST bucket
        // (yesterday), not today's.
        val minus5 = TimeZone.of("UTC-05:00")
        val runs = listOf(episodeCheck(at = utcMs(2026, 5, 12, 3, 0), inserted = 1))

        val buckets = bucketRunsByLocalDay(runs, LocalDate(2026, 5, 12), minus5)

        assertEquals(1, buckets[5].episodeInserted) // yesterday-local
        assertNull(buckets.last().episodeInserted) // today-local is empty
    }

    @Test
    fun weekdayLetter_returnsExpectedSingleLetterPerDay() {
        // 2026-05-11 is a Monday; walk forward through the week. Tuesday/Thursday
        // and Saturday/Sunday intentionally collapse to the same letter (`"T"`
        // and `"S"`) — standard single-letter sparkline-axis convention.
        assertEquals("M", weekdayLetter(LocalDate(2026, 5, 11)))
        assertEquals("T", weekdayLetter(LocalDate(2026, 5, 12))) // Tue
        assertEquals("W", weekdayLetter(LocalDate(2026, 5, 13)))
        assertEquals("T", weekdayLetter(LocalDate(2026, 5, 14))) // Thu — same letter, by design
        assertEquals("F", weekdayLetter(LocalDate(2026, 5, 15)))
        assertEquals("S", weekdayLetter(LocalDate(2026, 5, 16))) // Sat
        assertEquals("S", weekdayLetter(LocalDate(2026, 5, 17))) // Sun — same letter, by design
    }

    private fun episodeCheck(
        at: Long,
        inserted: Int,
    ): SchedulerRun = SchedulerRun(at = at, inserted = inserted, shows = 0, kind = SchedulerRunKind.EpisodeCheck.wire)

    private fun backup(at: Long): SchedulerRun = SchedulerRun(at = at, inserted = 0, shows = 0, kind = SchedulerRunKind.Backup.wire)

    private fun utcMs(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long {
        // Avoid pulling kotlinx-datetime's LocalDateTime constructor surface — express
        // every test time as an ISO-8601 UTC instant. Keeps the test self-evidently UTC.
        val iso =
            "$year-${pad(month)}-${pad(day)}T${pad(hour)}:${pad(minute)}:00Z"
        return Instant.parse(iso).toEpochMilliseconds()
    }

    private fun pad(n: Int): String = n.toString().padStart(2, '0')
}

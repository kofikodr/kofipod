// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the ETA math previously broken by hard-coding `nowApprox = startedAt + 1L`,
 * which made every active download appear to have finished in 1 ms — wildly
 * inflating throughput and producing meaningless ETAs.
 *
 * The contract: given total, done, started, and a real `now`, return remaining
 * seconds capped at 1 hour, or `null` when not enough information exists. The
 * formatter ([etaLabel]) wraps this and substitutes the em-dash on `null`; that
 * UI string is not under test here — the math is.
 */
class DownloadsScreenEtaTest {
    @Test
    fun returnsNullWhenTotalIsZero() {
        assertNull(computeEtaSeconds(totalBytes = 0L, downloadedBytes = 100L, startedAtMs = 1_000L, nowMs = 2_000L))
    }

    @Test
    fun returnsNullWhenTotalIsNegative() {
        assertNull(computeEtaSeconds(totalBytes = -1L, downloadedBytes = 100L, startedAtMs = 1_000L, nowMs = 2_000L))
    }

    @Test
    fun returnsNullWhenDownloadedIsZero() {
        // No bytes done → no throughput sample → no defensible ETA.
        assertNull(computeEtaSeconds(totalBytes = 1_000L, downloadedBytes = 0L, startedAtMs = 1_000L, nowMs = 2_000L))
    }

    @Test
    fun returnsNullWhenStartedIsNull() {
        assertNull(computeEtaSeconds(totalBytes = 1_000L, downloadedBytes = 100L, startedAtMs = null, nowMs = 2_000L))
    }

    @Test
    fun returnsNullWhenStartedIsZero() {
        // `startedAt == 0L` is the schema's "unset" sentinel — treat as "no
        // start time" rather than 1970 epoch, which would otherwise produce a
        // gigantic apparent elapsed time.
        assertNull(computeEtaSeconds(totalBytes = 1_000L, downloadedBytes = 100L, startedAtMs = 0L, nowMs = 2_000L))
    }

    @Test
    fun returnsNullWhenStartedIsNegative() {
        assertNull(computeEtaSeconds(totalBytes = 1_000L, downloadedBytes = 100L, startedAtMs = -1L, nowMs = 2_000L))
    }

    @Test
    fun halfDoneInOneSecond_extrapolatesToOneRemainingSecond() {
        // 500 of 1000 bytes done in 1000 ms ⇒ 0.5 bytes/ms ⇒ 500 remaining bytes
        // ⇒ 1000 ms ⇒ 1 second. This is the canonical case that the original
        // `started + 1L` bug regressed: with elapsed=1ms it would have computed
        // bps=500 and secs=0 instead of 1.
        val secs = computeEtaSeconds(totalBytes = 1_000L, downloadedBytes = 500L, startedAtMs = 1_000L, nowMs = 2_000L)
        assertNotNull(secs)
        assertEquals(1L, secs)
    }

    @Test
    fun tenPercentDoneInOneSecond_extrapolatesToNineSeconds() {
        // 100 of 1000 bytes done in 1000 ms ⇒ 0.1 bytes/ms ⇒ 900 remaining bytes
        // ⇒ 9000 ms ⇒ 9 seconds. Sanity-check a non-symmetric ratio.
        val secs = computeEtaSeconds(totalBytes = 1_000L, downloadedBytes = 100L, startedAtMs = 1_000L, nowMs = 2_000L)
        assertNotNull(secs)
        assertEquals(9L, secs)
    }

    @Test
    fun completedDownload_returnsNull() {
        // done == total ⇒ remaining == 0 ⇒ no defensible "time remaining"
        // estimate, so return null. In practice the row routes from
        // `state.downloading` to `state.completed` when the DB flips the row
        // to Completed, and this branch covers the brief window where
        // downloadedBytes == totalBytes but the state column hasn't flipped
        // yet — `"ETA —"` is more honest than `"ETA 0m 0s"` there.
        val secs = computeEtaSeconds(totalBytes = 1_000L, downloadedBytes = 1_000L, startedAtMs = 1_000L, nowMs = 2_000L)
        assertNull(secs)
    }

    @Test
    fun verySlowDownload_capsAtOneHour() {
        // 1 byte in 1 second on a 1 GB download would extrapolate to ~1 billion
        // seconds; the bar surface caps at 3600s (1 hour) so the label fits and
        // signals "very slow" without scrolling out to a multi-day countdown.
        val gigabyte = 1_000_000_000L
        val secs = computeEtaSeconds(totalBytes = gigabyte, downloadedBytes = 1L, startedAtMs = 1_000L, nowMs = 2_000L)
        assertEquals(3_600L, secs)
    }

    @Test
    fun sameMillisecondNowAndStarted_clampsElapsedToOneMs() {
        // If the row is published in the exact same millisecond it started
        // (rare but possible on fast emulators), the divisor would otherwise be
        // 0. The `max(1L, ...)` clamp keeps the function defined; the resulting
        // ETA is "based on a single-ms sample" — wrong but not crash-inducing,
        // and will self-correct on the next progress tick. Here 100 bytes in
        // 1 ms ⇒ 100 bytes/ms ⇒ 900 remaining ⇒ 9 ms ⇒ 0 s (sub-second integer
        // truncation, not a true "zero remaining time").
        val secs = computeEtaSeconds(totalBytes = 1_000L, downloadedBytes = 100L, startedAtMs = 5_000L, nowMs = 5_000L)
        assertNotNull(secs)
        assertEquals(0L, secs)
    }

    @Test
    fun nowBeforeStarted_isTreatedAsOneMsElapsed() {
        // Wall-clock skew or daylight-savings edge cases can produce now < started.
        // We clamp elapsed to 1 ms rather than returning a negative ETA or NaN —
        // a single bad sample heals on the next progress tick.
        val secs = computeEtaSeconds(totalBytes = 1_000L, downloadedBytes = 100L, startedAtMs = 10_000L, nowMs = 9_000L)
        assertNotNull(secs)
        // 100 done in 1 ms (clamped) ⇒ 100 bytes/ms ⇒ 900 remaining ⇒ 9 ms ⇒ 0 s.
        assertEquals(0L, secs)
    }

    @Test
    fun formatEtaLabel_nullSeconds_returnsEmDashLabel() {
        // Pin the em-dash substitution so a future copy change (e.g. swapping
        // "ETA —" for "—" alone, or changing the prefix) breaks loudly.
        assertEquals("ETA —", formatEtaLabel(null))
    }

    @Test
    fun formatEtaLabel_decomposesSecondsIntoMinutesAndSeconds() {
        // 62 s → 1 m 2 s. Pins the minute/second decomposition; also covers
        // the typical mid-download display value the user will actually see.
        assertEquals("ETA 1m 2s", formatEtaLabel(62L))
    }

    @Test
    fun formatEtaLabel_zeroSeconds_returnsZeroMZeroS() {
        // Completed downloads format as "ETA 0m 0s" rather than blank or em-dash,
        // so the row keeps a stable label width as it transitions from active to
        // done. (The label is hidden separately once the row enters the
        // completed bucket; this just pins the formatter contract.)
        assertEquals("ETA 0m 0s", formatEtaLabel(0L))
    }

    @Test
    fun longRunningDownload_handlesMultiMinuteEta() {
        // 30 MB done in 60 s on a 60 MB download ⇒ 0.5 MB/s ⇒ 30 MB remaining
        // ⇒ 60 s remaining. Confirms minute-scale arithmetic is consistent
        // with the second-scale tests.
        val secs =
            computeEtaSeconds(
                totalBytes = 60L * 1_000_000L,
                downloadedBytes = 30L * 1_000_000L,
                startedAtMs = 1_000L,
                nowMs = 61_000L,
            )
        assertNotNull(secs)
        assertEquals(60L, secs)
    }
}

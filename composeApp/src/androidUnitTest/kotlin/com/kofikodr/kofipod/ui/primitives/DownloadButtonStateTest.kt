// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.primitives

import com.kofikodr.kofipod.db.Download
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pins the pure mapping from a [Download]? SQLDelight row to the [DownloadButtonState]
 * the three download buttons render. The mapping function carries the entire visual
 * decision; the button composables only render whatever it returns.
 *
 * Each branch corresponds to a `Download.state` string that the foreground
 * [com.kofikodr.kofipod.downloads.DownloadService] is allowed to write
 * ([com.kofikodr.kofipod.data.repo.DownloadRepository] is the sole consumer).
 * If the engine starts writing a new state string, this test surface will quietly
 * fall through to [DownloadButtonState.Idle] — that's the regression to watch.
 */
class DownloadButtonStateTest {
    @Test
    fun nullRow_isIdle() {
        // No DB row at all — episode has never been enqueued. The button should
        // invite the user to download.
        assertEquals(DownloadButtonState.Idle, (null as Download?).toDownloadButtonState())
    }

    @Test
    fun completed_withLocalPath_isDone() {
        // Terminal success path. localPath is non-blank → file actually exists
        // on disk (the repository self-heals dangling-localPath rows elsewhere,
        // so by the time UI sees a Completed+path row, the file is real).
        assertEquals(
            DownloadButtonState.Done,
            row(state = "Completed", localPath = "/data/local/files/ep.mp3", bytes = 5_000_000L, total = 5_000_000L)
                .toDownloadButtonState(),
        )
    }

    @Test
    fun completed_withNullLocalPath_isPending() {
        // Defensive: a Completed row whose localPath hasn't been written yet
        // (between updateState and markCompleted) shouldn't claim Done — that
        // would briefly show the Trash (downloaded) affordance and then revert.
        assertEquals(
            DownloadButtonState.Pending,
            row(state = "Completed", localPath = null, bytes = 1L, total = 1L).toDownloadButtonState(),
        )
    }

    @Test
    fun completed_withBlankLocalPath_isPending() {
        // Whitespace-only path is the load-bearing case — exercises the
        // `isBlank` half of `isNullOrBlank` independently of the null branch.
        // A weaker guard (`!= null && isNotEmpty()`) would slip through here
        // and silently treat a blank path as Done, surfacing the Trash
        // (downloaded) affordance on a row whose file isn't actually playable.
        assertEquals(
            DownloadButtonState.Pending,
            row(state = "Completed", localPath = "   ", bytes = 1L, total = 1L).toDownloadButtonState(),
        )
    }

    @Test
    fun failed_isFailed() {
        // Terminal failure. Button surfaces red Download icon + retry semantics.
        assertEquals(
            DownloadButtonState.Failed,
            row(state = "Failed").toDownloadButtonState(),
        )
    }

    @Test
    fun queued_isPending() {
        // Engine accepted the job but no first progress byte yet. Indeterminate
        // arc is the right affordance.
        assertEquals(
            DownloadButtonState.Pending,
            row(state = "Queued").toDownloadButtonState(),
        )
    }

    @Test
    fun waitingForWifi_isPending() {
        // Engine deferred until Wi-Fi. Same indeterminate affordance as Queued;
        // the caller wires up a separate text label if it needs to distinguish.
        assertEquals(
            DownloadButtonState.Pending,
            row(state = "WaitingForWifi").toDownloadButtonState(),
        )
    }

    @Test
    fun paused_isIdle_soCancelledDownloadsCanReEnqueue() {
        // DownloadRepository.cancel() writes "Paused". Once the engine has
        // cancelled the job it stops emitting events for this row, so leaving
        // the button in Pending would strand it on the spinning-arc visual
        // forever. Mapping to Idle returns the Download icon and lets the user
        // re-enqueue with another tap — the contract verified end-to-end on a
        // Pixel_9a emulator.
        assertEquals(
            DownloadButtonState.Idle,
            row(state = "Paused").toDownloadButtonState(),
        )
    }

    @Test
    fun downloading_withKnownTotal_isInProgressFraction() {
        // The happy path. 250_000 of 1_000_000 bytes = 25% determinate arc.
        val s = row(state = "Downloading", bytes = 250_000L, total = 1_000_000L).toDownloadButtonState()
        val ip = assertIs<DownloadButtonState.InProgress>(s)
        assertEquals(0.25f, ip.fraction, 0.0001f)
    }

    @Test
    fun downloading_withZeroTotal_isPending() {
        // First emission from DownloadService can arrive before Content-Length
        // is parsed — totalBytes is still 0. Show an indeterminate arc rather
        // than a zero-percent determinate arc that looks frozen.
        assertEquals(
            DownloadButtonState.Pending,
            row(state = "Downloading", bytes = 1L, total = 0L).toDownloadButtonState(),
        )
    }

    @Test
    fun downloading_withBytesExceedingTotal_isClampedToOne() {
        // Defensive: some servers report Content-Length below the actual body
        // (chunked encoding, gzip-decoded length mismatch). Clamping prevents
        // an arc that exceeds 360°.
        val s = row(state = "Downloading", bytes = 10_000L, total = 5_000L).toDownloadButtonState()
        val ip = assertIs<DownloadButtonState.InProgress>(s)
        assertEquals(1.0f, ip.fraction, 0.0001f)
    }

    @Test
    fun downloading_zeroBytesWithKnownTotal_isInProgressZero() {
        // Distinct from `downloading_withZeroTotal_isPending`: here totalBytes
        // > 0 (engine has parsed Content-Length) but the first body bytes haven't
        // streamed yet. We want a determinate 0% arc, not the indeterminate
        // spinner — visually it says "we know the size, we're starting now".
        val s = row(state = "Downloading", bytes = 0L, total = 100L).toDownloadButtonState()
        val ip = assertIs<DownloadButtonState.InProgress>(s)
        assertEquals(0.0f, ip.fraction, 0.0001f)
    }

    @Test
    fun downloading_boundarySweep_pinsExactFractions() {
        // Exact fractions across the unit interval. The prior version of this
        // test only checked `fraction in 0f..1f` — which is guaranteed by
        // `coerceIn` and therefore catches no realistic regression. Pinning
        // exact values catches a sign flip, a swap of bytes/total, or an
        // integer-division rounding mistake.
        val s50 = row(state = "Downloading", bytes = 50L, total = 100L).toDownloadButtonState()
        assertEquals(0.5f, assertIs<DownloadButtonState.InProgress>(s50).fraction, 0.0001f)
        val s75 = row(state = "Downloading", bytes = 75L, total = 100L).toDownloadButtonState()
        assertEquals(0.75f, assertIs<DownloadButtonState.InProgress>(s75).fraction, 0.0001f)
        val s100 = row(state = "Downloading", bytes = 100L, total = 100L).toDownloadButtonState()
        assertEquals(1.0f, assertIs<DownloadButtonState.InProgress>(s100).fraction, 0.0001f)
    }

    @Test
    fun unknownState_isIdle() {
        // If the engine ever writes a state string the mapper doesn't recognise,
        // the button reverts to Idle — the user can re-tap and re-enqueue.
        // Better than a frozen indeterminate spinner with no escape.
        assertEquals(
            DownloadButtonState.Idle,
            row(state = "ImaginaryState").toDownloadButtonState(),
        )
    }

    // ---- helpers ------------------------------------------------------------

    private fun row(
        state: String,
        localPath: String? = null,
        bytes: Long = 0L,
        total: Long = 0L,
    ): Download =
        Download(
            episodeId = "ep-1",
            state = state,
            localPath = localPath,
            downloadedBytes = bytes,
            totalBytes = total,
            source = "Manual",
            startedAt = 0L,
            completedAt = null,
            errorMessage = null,
        )
}

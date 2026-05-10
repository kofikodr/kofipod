// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.snippet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-math tests for [resolveHandleHit] — the hit-zone gate that turned the
 * snippet waveform from "any touch grabs the closer handle" (which was
 * erratic for middle-of-waveform drags) into "only taps within
 * HANDLE_HIT_RADIUS_DP of a handle's pixel centre register."
 *
 * Test fixture: viewport `[0, 60_000]ms`, width `600px`, hit radius `60px`.
 * With trim `[15_000, 45_000]` that gives `startX = 150`, `endX = 450`,
 * which is wide enough to leave a clean dead-zone in the middle. The tight
 * cases use trim `[29_500, 30_500]` (1s = MIN_WINDOW_MS) so handles overlap
 * inside their radii — that's the only place where the closer-of-two rule
 * actually fires.
 */
class SnippetHandleHitTestTest {
    @Test
    fun `tap directly on start handle returns Start`() {
        val hit =
            resolveHandleHit(
                touchXPx = 150f,
                startMs = 15_000L,
                endMs = 45_000L,
                viewportStartMs = 0L,
                viewportEndMs = 60_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertEquals(SnippetHandle.Start, hit)
    }

    @Test
    fun `tap directly on end handle returns End`() {
        val hit =
            resolveHandleHit(
                touchXPx = 450f,
                startMs = 15_000L,
                endMs = 45_000L,
                viewportStartMs = 0L,
                viewportEndMs = 60_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertEquals(SnippetHandle.End, hit)
    }

    @Test
    fun `tap dead-center between widely separated handles returns null`() {
        // Centre between 150 and 450 = 300. Distance from each handle = 150,
        // well outside the 60px radius. The user's middle-drag complaint.
        val hit =
            resolveHandleHit(
                touchXPx = 300f,
                startMs = 15_000L,
                endMs = 45_000L,
                viewportStartMs = 0L,
                viewportEndMs = 60_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertNull(hit)
    }

    @Test
    fun `tap exactly on start handle left radius boundary returns Start`() {
        // startX = 150, hit radius 60 → left boundary at 90. The `<=`
        // comparison means boundary counts as a hit.
        val hit =
            resolveHandleHit(
                touchXPx = 90f,
                startMs = 15_000L,
                endMs = 45_000L,
                viewportStartMs = 0L,
                viewportEndMs = 60_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertEquals(SnippetHandle.Start, hit)
    }

    @Test
    fun `tap exactly on start handle right radius boundary returns Start`() {
        // startX = 150, hit radius 60 → right boundary at 210. Mirror of the
        // left-boundary test; together they pin the `<=` comparison on both
        // sides of the start handle.
        val hit =
            resolveHandleHit(
                touchXPx = 210f,
                startMs = 15_000L,
                endMs = 45_000L,
                viewportStartMs = 0L,
                viewportEndMs = 60_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertEquals(SnippetHandle.Start, hit)
    }

    @Test
    fun `tap one px outside start handle radius returns null`() {
        // startX = 150, hit radius 60 → boundary at 90. Tap at 89 = 61px away.
        // Also far from end (361px), so resolves to null, not End.
        val hit =
            resolveHandleHit(
                touchXPx = 89f,
                startMs = 15_000L,
                endMs = 45_000L,
                viewportStartMs = 0L,
                viewportEndMs = 60_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertNull(hit)
    }

    @Test
    fun `tap inside start handle radius offset from centre returns Start`() {
        // startX = 150. Tap at 180 = 30px right of centre, well inside the
        // 60px radius and 270px from end. Confirms the start hit-zone
        // accepts the interior, not just the exact pixel centre.
        val hit =
            resolveHandleHit(
                touchXPx = 180f,
                startMs = 15_000L,
                endMs = 45_000L,
                viewportStartMs = 0L,
                viewportEndMs = 60_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertEquals(SnippetHandle.Start, hit)
    }

    @Test
    fun `tap exactly on end handle left radius boundary returns End`() {
        // endX = 450, hit radius 60 → left boundary at 390. Mirror of the
        // start-handle boundary tests for the End branch's `<=` correctness.
        val hit =
            resolveHandleHit(
                touchXPx = 390f,
                startMs = 15_000L,
                endMs = 45_000L,
                viewportStartMs = 0L,
                viewportEndMs = 60_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertEquals(SnippetHandle.End, hit)
    }

    @Test
    fun `tap exactly on end handle right radius boundary returns End`() {
        // endX = 450, hit radius 60 → right boundary at 510.
        val hit =
            resolveHandleHit(
                touchXPx = 510f,
                startMs = 15_000L,
                endMs = 45_000L,
                viewportStartMs = 0L,
                viewportEndMs = 60_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertEquals(SnippetHandle.End, hit)
    }

    @Test
    fun `tap one px outside end handle radius returns null`() {
        // endX = 450, hit radius 60 → right boundary at 510. Tap at 511 =
        // 61px away. Also far from start (361px), so resolves to null.
        val hit =
            resolveHandleHit(
                touchXPx = 511f,
                startMs = 15_000L,
                endMs = 45_000L,
                viewportStartMs = 0L,
                viewportEndMs = 60_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertNull(hit)
    }

    @Test
    fun `tap inside end radius and outside start radius returns End`() {
        // endX = 450, tap at 400 = 50px from end (inside radius), 250px from
        // start (outside radius). Confirms we don't blindly tie-break to Start
        // — End wins when only End is reachable.
        val hit =
            resolveHandleHit(
                touchXPx = 400f,
                startMs = 15_000L,
                endMs = 45_000L,
                viewportStartMs = 0L,
                viewportEndMs = 60_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertEquals(SnippetHandle.End, hit)
    }

    @Test
    fun `equidistant tap inside both radii (handles at min span) tie-breaks to Start`() {
        // 1s trim at viewport span 60s, width 600 → startX=295, endX=305.
        // Tap at 300 → 5px from each. Both inside radius. Tie → Start.
        val hit =
            resolveHandleHit(
                touchXPx = 300f,
                startMs = 29_500L,
                endMs = 30_500L,
                viewportStartMs = 0L,
                viewportEndMs = 60_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertEquals(SnippetHandle.Start, hit)
    }

    @Test
    fun `tap closer to start than end (both inside radius) returns Start`() {
        // Min-span fixture: startX=295, endX=305. Tap at 296. dStart = 1,
        // dEnd = 9 — both within 60px radius, but Start is strictly closer.
        // Verifies the `dStart < dEnd` half of the `dStart <= dEnd` rule —
        // without this, a hardcoded `return Start` could pass every other
        // Start-returning test.
        val hit =
            resolveHandleHit(
                touchXPx = 296f,
                startMs = 29_500L,
                endMs = 30_500L,
                viewportStartMs = 0L,
                viewportEndMs = 60_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertEquals(SnippetHandle.Start, hit)
    }

    @Test
    fun `tap closer to end than start (both inside radius) returns End`() {
        // Same min-span fixture as the tie test, but shifted 4px toward End.
        // dStart = 9, dEnd = 1 → End. Confirms tie-break only fires on actual ties.
        val hit =
            resolveHandleHit(
                touchXPx = 304f,
                startMs = 29_500L,
                endMs = 30_500L,
                viewportStartMs = 0L,
                viewportEndMs = 60_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertEquals(SnippetHandle.End, hit)
    }

    @Test
    fun `tap on end handle after viewport has zoomed in resolves correctly`() {
        // Regression for the original bug: viewport rescales mid-edit to
        // [29_500, 30_500] (fully zoomed-in min-span trim). startX=0, endX=600.
        // Tap at 550 → 50px from end (inside), 550px from start (outside).
        val hit =
            resolveHandleHit(
                touchXPx = 550f,
                startMs = 29_500L,
                endMs = 30_500L,
                viewportStartMs = 29_500L,
                viewportEndMs = 30_500L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertEquals(SnippetHandle.End, hit)
    }

    @Test
    fun `tap on start handle after viewport has zoomed in resolves correctly`() {
        // Companion to the End-side fully-zoomed test: same fixture, tap on
        // the left edge where startX=0. Confirms a tap at pixel 0 is still
        // recognised as a start-handle grab when the viewport zooms in.
        val hit =
            resolveHandleHit(
                touchXPx = 50f,
                startMs = 29_500L,
                endMs = 30_500L,
                viewportStartMs = 29_500L,
                viewportEndMs = 30_500L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertEquals(SnippetHandle.Start, hit)
    }

    @Test
    fun `end handle projecting past right viewport edge is still grabbable from the visible right edge`() {
        // Mirror of the start-side off-viewport case below. endMs > vEnd so
        // endX projects beyond widthPx: span = 60_000, endMs - vStart = 60_500
        // → endX = 60_500/60_000 * 600 = 605 (5px past the right edge). Tap
        // at the visible right edge (x=600) is 5px from the off-screen end
        // handle, well inside the 60px radius → End. Locks in the symmetric
        // contract for the right side.
        val hit =
            resolveHandleHit(
                touchXPx = 600f,
                startMs = 25_000L,
                endMs = 70_500L,
                viewportStartMs = 10_000L,
                viewportEndMs = 70_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertEquals(SnippetHandle.End, hit)
    }

    @Test
    fun `handle that projects outside the viewport is still hit-tested without crashing`() {
        // Edge case during the gesture-freeze handover: trim and viewport can
        // briefly disagree (handle ms outside the viewport range), giving a
        // negative or width-exceeding handle X. The function must compute a
        // deterministic answer rather than crash. Here startMs < vStart →
        // startX = -5 (just off-screen left), endX = 250. Tap at the visible
        // left edge (x=0) is 5px from the off-screen start handle, well
        // inside the 60px radius → Start. (Documents the contract:
        // off-screen handles are still grabbable from the nearest viewport
        // edge while the freeze settles.)
        val hit =
            resolveHandleHit(
                touchXPx = 0f,
                startMs = 9_500L,
                endMs = 35_000L,
                viewportStartMs = 10_000L,
                viewportEndMs = 70_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertEquals(SnippetHandle.Start, hit)
    }

    @Test
    fun `degenerate viewport with zero span returns null`() {
        // First-frame race: viewport not yet computed, vEnd == vStart. The
        // pure helper must not divide by zero — it returns null and the
        // pointer-input no-ops.
        val hit =
            resolveHandleHit(
                touchXPx = 100f,
                startMs = 15_000L,
                endMs = 45_000L,
                viewportStartMs = 60_000L,
                viewportEndMs = 60_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertNull(hit)
    }

    @Test
    fun `degenerate viewport with negative span returns null`() {
        // Defensive: a viewport pair where vEnd < vStart should not crash and
        // should not produce a "both handles equidistant from x=0" Start hit.
        val hit =
            resolveHandleHit(
                touchXPx = 100f,
                startMs = 15_000L,
                endMs = 45_000L,
                viewportStartMs = 60_000L,
                viewportEndMs = 30_000L,
                widthPx = 600f,
                hitRadiusPx = 60f,
            )
        assertNull(hit)
    }

    @Test
    fun `degenerate width zero returns null`() {
        // First-frame race: onSizeChanged hasn't fired, widthPx is still 0.
        // Without the guard, every handle X would compute to 0 and any touch
        // would resolve to Start.
        val hit =
            resolveHandleHit(
                touchXPx = 100f,
                startMs = 15_000L,
                endMs = 45_000L,
                viewportStartMs = 0L,
                viewportEndMs = 60_000L,
                widthPx = 0f,
                hitRadiusPx = 60f,
            )
        assertNull(hit)
    }
}

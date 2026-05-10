// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SnippetPreviewTickTest {
    @Test
    fun `project at zero elapsed returns base position`() {
        val r = SnippetPreviewTick.project(baseMs = 10_000, elapsedMs = 0, speed = 1f, endMs = 30_000)
        val cont = assertIs<SnippetPreviewTick.Result.Continue>(r)
        assertEquals(10_000L, cont.positionMs)
    }

    @Test
    fun `project advances at 1x speed`() {
        val r = SnippetPreviewTick.project(baseMs = 10_000, elapsedMs = 5_000, speed = 1f, endMs = 30_000)
        val cont = assertIs<SnippetPreviewTick.Result.Continue>(r)
        assertEquals(15_000L, cont.positionMs)
    }

    @Test
    fun `project advances faster at 1_5x speed`() {
        val r = SnippetPreviewTick.project(baseMs = 10_000, elapsedMs = 4_000, speed = 1.5f, endMs = 30_000)
        val cont = assertIs<SnippetPreviewTick.Result.Continue>(r)
        // 10_000 + (4_000 * 1.5) = 16_000
        assertEquals(16_000L, cont.positionMs)
    }

    @Test
    fun `project returns End when reaching endMs`() {
        val r = SnippetPreviewTick.project(baseMs = 10_000, elapsedMs = 20_000, speed = 1f, endMs = 30_000)
        val end = assertIs<SnippetPreviewTick.Result.End>(r)
        // End snaps to endMs so the playhead lands exactly on the trim handle
        // instead of overshooting and jittering off-canvas.
        assertEquals(30_000L, end.positionMs)
    }

    @Test
    fun `project returns End when overshooting endMs`() {
        val r = SnippetPreviewTick.project(baseMs = 10_000, elapsedMs = 25_000, speed = 1f, endMs = 30_000)
        val end = assertIs<SnippetPreviewTick.Result.End>(r)
        assertEquals(30_000L, end.positionMs)
    }

    @Test
    fun `project treats negative elapsed as zero`() {
        // Clock skew or NTP correction during preview must not yank the playhead
        // backward — it should sit at base until time catches up.
        val r = SnippetPreviewTick.project(baseMs = 10_000, elapsedMs = -1_500, speed = 1f, endMs = 30_000)
        val cont = assertIs<SnippetPreviewTick.Result.Continue>(r)
        assertEquals(10_000L, cont.positionMs)
    }

    @Test
    fun `project clamps zero speed to a positive minimum`() {
        // A zero or negative speed reading from the player would freeze the
        // scrubber. Use the floor so the line still advances; the player's own
        // playback speed cannot be zero in normal use.
        val r = SnippetPreviewTick.project(baseMs = 10_000, elapsedMs = 1_000, speed = 0f, endMs = 30_000)
        val cont = assertIs<SnippetPreviewTick.Result.Continue>(r)
        // 10_000 + (1_000 * 0.1f) = 10_100
        assertEquals(10_100L, cont.positionMs)
    }

    @Test
    fun `project clamps negative speed to a positive minimum`() {
        val r = SnippetPreviewTick.project(baseMs = 10_000, elapsedMs = 1_000, speed = -2f, endMs = 30_000)
        val cont = assertIs<SnippetPreviewTick.Result.Continue>(r)
        assertEquals(10_100L, cont.positionMs)
    }

    @Test
    fun `resyncIfDrifted returns null when within threshold`() {
        // 500ms drift is below the 750ms tolerance — no resync.
        assertNull(SnippetPreviewTick.resyncIfDrifted(projectedMs = 12_000, playerPositionMs = 12_500))
    }

    @Test
    fun `resyncIfDrifted returns null exactly at threshold`() {
        // 750ms drift is the boundary — must not trigger resync (`> threshold`,
        // not `>= threshold`).
        assertNull(SnippetPreviewTick.resyncIfDrifted(projectedMs = 12_000, playerPositionMs = 12_750))
    }

    @Test
    fun `resyncIfDrifted returns player position when drift exceeds threshold`() {
        val r = SnippetPreviewTick.resyncIfDrifted(projectedMs = 12_000, playerPositionMs = 13_000)
        assertEquals(13_000L, r)
    }

    @Test
    fun `resyncIfDrifted handles negative drift`() {
        // Player jumped backward (e.g. user yanked system seek bar). Resync.
        val r = SnippetPreviewTick.resyncIfDrifted(projectedMs = 13_000, playerPositionMs = 12_000)
        assertEquals(12_000L, r)
    }

    @Test
    fun `resyncIfDrifted with equal positions returns null`() {
        // Zero drift — projected and authoritative positions agree exactly.
        // Must not trigger a spurious resync.
        assertNull(SnippetPreviewTick.resyncIfDrifted(projectedMs = 12_000, playerPositionMs = 12_000))
    }

    @Test
    fun `project with zero-length window ends immediately at base`() {
        // Degenerate window where endMs == baseMs (user dragged trim handles
        // together). The poll loop must terminate on the first tick instead of
        // running forever — so even at zero elapsed, project returns End.
        val r = SnippetPreviewTick.project(baseMs = 10_000, elapsedMs = 0, speed = 1f, endMs = 10_000)
        val end = assertIs<SnippetPreviewTick.Result.End>(r)
        assertEquals(10_000L, end.positionMs)
    }

    @Test
    fun `project truncates fractional sub-millisecond accumulation`() {
        // (3 * 1.5f).toLong() == 4 — the 0.5ms fraction is dropped per tick.
        // Documented as an accepted approximation: the resync mechanism corrects
        // accumulated drift against the player's authoritative positionMs.
        val r = SnippetPreviewTick.project(baseMs = 10_000, elapsedMs = 3, speed = 1.5f, endMs = 30_000)
        val cont = assertIs<SnippetPreviewTick.Result.Continue>(r)
        assertEquals(10_004L, cont.positionMs)
    }
}

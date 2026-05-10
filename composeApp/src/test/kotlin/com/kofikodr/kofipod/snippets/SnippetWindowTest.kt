// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetWindowTest {
    @Test
    fun `last 60s window from mid episode returns previous 60 seconds`() {
        val w = SnippetWindow.computeLast60sWindow(positionMs = 120_000L, durationMs = 600_000L)
        assertEquals(60_000L, w.startMs)
        assertEquals(120_000L, w.endMs)
    }

    @Test
    fun `last 60s window early in episode clamps start to zero`() {
        val w = SnippetWindow.computeLast60sWindow(positionMs = 30_000L, durationMs = 600_000L)
        assertEquals(0L, w.startMs)
        assertEquals(30_000L, w.endMs)
    }

    @Test
    fun `last 60s window at episode start yields 1ms zero-length-safe window`() {
        // Position 0 + duration 0 should still yield a non-negative span.
        val w = SnippetWindow.computeLast60sWindow(positionMs = 0L, durationMs = 0L)
        assertEquals(0L, w.startMs)
        assertEquals(0L, w.endMs)
    }

    @Test
    fun `clamp pulls negative start up to zero`() {
        val w = SnippetWindow.clampWindow(startMs = -500L, endMs = 5_000L, durationMs = 600_000L)
        assertEquals(0L, w.startMs)
        assertEquals(5_000L, w.endMs)
    }

    @Test
    fun `clamp pulls past-duration end down to duration`() {
        val w = SnippetWindow.clampWindow(startMs = 100_000L, endMs = 700_000L, durationMs = 600_000L)
        assertEquals(100_000L, w.startMs)
        assertEquals(600_000L, w.endMs)
    }

    @Test
    fun `clamp swaps reversed start and end`() {
        val w = SnippetWindow.clampWindow(startMs = 80_000L, endMs = 20_000L, durationMs = 600_000L)
        assertEquals(20_000L, w.startMs)
        assertEquals(80_000L, w.endMs)
    }

    @Test
    fun `clamp enforces minimum 1 second span by extending end`() {
        // 500ms span isn't renderable. Extend end to start + 1000ms.
        val w = SnippetWindow.clampWindow(startMs = 10_000L, endMs = 10_500L, durationMs = 600_000L)
        assertEquals(10_000L, w.startMs)
        assertEquals(11_000L, w.endMs)
    }

    @Test
    fun `clamp prefers extending end but falls back to pulling start when at duration`() {
        // start = end = duration. Can't extend end (already at duration), so pull start back.
        val w = SnippetWindow.clampWindow(startMs = 600_000L, endMs = 600_000L, durationMs = 600_000L)
        assertEquals(599_000L, w.startMs)
        assertEquals(600_000L, w.endMs)
    }

    @Test
    fun `formatTimestampDeci formats sub-second precision`() {
        assertEquals("00:00.0", SnippetWindow.formatTimestampDeci(0L))
        assertEquals("00:00.5", SnippetWindow.formatTimestampDeci(500L))
        assertEquals("00:01.2", SnippetWindow.formatTimestampDeci(1_234L))
        assertEquals("01:30.0", SnippetWindow.formatTimestampDeci(90_000L))
        assertEquals("12:34.5", SnippetWindow.formatTimestampDeci((12 * 60 + 34) * 1000L + 500L))
        assertEquals("60:00.0", SnippetWindow.formatTimestampDeci(60 * 60 * 1000L))
    }
}

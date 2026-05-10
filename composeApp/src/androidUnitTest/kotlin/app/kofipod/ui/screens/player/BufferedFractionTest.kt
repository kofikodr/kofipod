// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class BufferedFractionTest {
    @Test
    fun `local source always reports full regardless of bufferedMs`() {
        // The whole episode is on disk — UI shouldn't wait for ExoPlayer's bufferedPosition
        // to ramp up before showing the secondary track at full width.
        assertEquals(1f, bufferedFraction(durationMs = 60_000, bufferedMs = 0, isLocalSource = true), 0f)
        assertEquals(1f, bufferedFraction(durationMs = 60_000, bufferedMs = 5_000, isLocalSource = true), 0f)
    }

    @Test
    fun `local source with zero duration still reports full`() {
        // Edge case: file just loaded, duration not yet resolved. Returning 1f here is
        // marginally aggressive but harmless — the bar reaches full as soon as duration
        // resolves, and durationMs<=0 means there's no scrubber visible anyway.
        assertEquals(1f, bufferedFraction(durationMs = 0, bufferedMs = 0, isLocalSource = true), 0f)
    }

    @Test
    fun `streaming source returns proportional fraction`() {
        assertEquals(0.5f, bufferedFraction(durationMs = 100_000, bufferedMs = 50_000, isLocalSource = false), 0f)
        assertEquals(0.25f, bufferedFraction(durationMs = 100_000, bufferedMs = 25_000, isLocalSource = false), 0f)
        // Non-divisible ratio — guards against an integer-division regression
        // (e.g. (bufferedMs / durationMs).toFloat() instead of Float division).
        assertEquals(1f / 3f, bufferedFraction(durationMs = 3_000, bufferedMs = 1_000, isLocalSource = false), 0.001f)
    }

    @Test
    fun `streaming source clamps when buffered exceeds duration`() {
        // ExoPlayer can briefly report bufferedPosition slightly past duration during
        // STATE_ENDED — clamp prevents drawing past the track end.
        assertEquals(1f, bufferedFraction(durationMs = 100_000, bufferedMs = 200_000, isLocalSource = false), 0f)
    }

    @Test
    fun `streaming source returns zero when duration unknown`() {
        assertEquals(0f, bufferedFraction(durationMs = 0, bufferedMs = 5_000, isLocalSource = false), 0f)
        assertEquals(0f, bufferedFraction(durationMs = -1, bufferedMs = 5_000, isLocalSource = false), 0f)
    }

    @Test
    fun `streaming source returns zero when nothing buffered`() {
        assertEquals(0f, bufferedFraction(durationMs = 100_000, bufferedMs = 0, isLocalSource = false), 0f)
    }

    @Test
    fun `streaming source clamps negative bufferedMs to zero`() {
        // ExoPlayer can return -1 (C.TIME_UNSET) before the source is prepared.
        assertEquals(0f, bufferedFraction(durationMs = 100_000, bufferedMs = -1, isLocalSource = false), 0f)
    }
}

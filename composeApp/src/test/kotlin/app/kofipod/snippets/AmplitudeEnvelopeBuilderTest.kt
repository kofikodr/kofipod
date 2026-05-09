// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmplitudeEnvelopeBuilderTest {
    private val sampleRate = 44_100
    private val fps = 30
    private val frameCount = 30 // 1 second of video at 30 fps
    private val barCount = 64
    private val sliceWidthMs = 50

    private fun secondOfSilence(): ShortArray = ShortArray(sampleRate) { 0 }

    private fun secondOfFullScale(): ShortArray = ShortArray(sampleRate) { Short.MAX_VALUE }

    private fun envelopeFor(
        pcm: ShortArray,
        smoothingAlpha: Float = 0.4f,
    ): AmplitudeEnvelope =
        AmplitudeEnvelopeBuilder.build(
            pcm = pcm,
            sampleRate = sampleRate,
            frameCount = frameCount,
            barCount = barCount,
            sliceWidthMs = sliceWidthMs,
            fps = fps,
            smoothingAlpha = smoothingAlpha,
        )

    @Test
    fun silent_input_produces_all_zero_envelope() {
        val env = envelopeFor(secondOfSilence())
        assertEquals(frameCount, env.frameCount)
        assertEquals(barCount, env.barCount)
        for (f in 0 until frameCount) {
            val bars = env.barsAt(f)
            for (b in 0 until barCount) {
                assertEquals(0f, bars[b], "frame=$f bar=$b should be 0 for silent pcm")
            }
        }
    }

    @Test
    fun constant_full_scale_input_saturates_in_range_bars_unsmoothed() {
        // DC at Short.MAX_VALUE has RMS = Short.MAX_VALUE. Every slice in range
        // hits clipMaxRms exactly, so the in-range bars normalise to 1.0.
        // alpha=1 disables smoothing so this test isolates the RMS + normalisation
        // arithmetic from the per-bar low-pass response. (The smoothing pass is
        // exercised separately in `smoothing_damps_a_step_transition`.)
        val env = envelopeFor(secondOfFullScale(), smoothingAlpha = 1f)
        // tBarMs ∈ [0, clipMs) at frame 15 → b - 32 ∈ [-10, 10).
        val frame = frameCount / 2
        val bars = env.barsAt(frame)
        for (offset in -10..9) {
            val b = barCount / 2 + offset
            assertEquals(1f, bars[b], absoluteTolerance = 1e-3f, "frame=$frame bar=$b should saturate")
        }
    }

    @Test
    fun loud_burst_at_midpoint_peaks_at_center_frame_and_center_bar() {
        // 100ms loud burst centred at clipMs/2 = 500ms; silence elsewhere.
        // Burst spans samples [475ms, 525ms) = [20947, 23152) → ~2205 samples.
        val pcm = ShortArray(sampleRate)
        val burstStart = (sampleRate * 475L / 1000L).toInt()
        val burstEnd = (sampleRate * 525L / 1000L).toInt()
        for (i in burstStart until burstEnd) pcm[i] = Short.MAX_VALUE
        val env = envelopeFor(pcm)

        val centerFrame = frameCount / 2 // frame 15 → tCenter = 500ms
        val centerBar = barCount / 2 // tBar = tCenter + 0
        val centerValue = env.barsAt(centerFrame)[centerBar]

        // Centre cell sees the burst directly — expect a high value. The
        // smoothing pass attenuates the first frame the burst lands on, but
        // by the centre frame the recursion has had time to climb.
        assertTrue(
            centerValue > 0.5f,
            "centre frame/bar value ($centerValue) should be high for a midpoint burst",
        )

        // Frame 0 / centre bar samples a slice at tBar=0ms (well before the
        // burst); frame 0 / bar 0 samples tBar < 0 (out of range). Both must
        // be lower than the centre cell.
        val edgeFrameCenterBar = env.barsAt(0)[centerBar]
        assertTrue(
            centerValue > edgeFrameCenterBar,
            "centre cell ($centerValue) should peak above frame-0/bar-$centerBar ($edgeFrameCenterBar)",
        )

        // Bars far from the burst within the centre frame should also be
        // smaller — they're sampling silence regions.
        val farBarValue = env.barsAt(centerFrame)[0] // tBar = 500 - 32*50 = -1100 → out of range, 0
        assertEquals(0f, farBarValue, absoluteTolerance = 1e-6f)
    }

    @Test
    fun bars_outside_clip_window_are_zero_unsmoothed() {
        // alpha=1 → raw values pass through. We're verifying the in-range
        // window logic, not the smoothing tail. Smoothing's "fade-out" of an
        // out-of-range bar is desirable visual behaviour and is covered by
        // `smoothing_damps_a_step_transition`.
        val env = envelopeFor(secondOfFullScale(), smoothingAlpha = 1f)

        // Frame 0: tCenter = 0ms. Bars with b < barCount/2 have tBar < 0 → 0.
        val frame0 = env.barsAt(0)
        for (b in 0 until barCount / 2) {
            assertEquals(0f, frame0[b], absoluteTolerance = 1e-6f, "frame=0 bar=$b should be 0 (tBar < 0)")
        }

        // Last frame: tCenter ≈ ((frameCount-1) * 1000 / fps) = 967ms.
        // tBar = 967 + (b - 32) * 50 ≥ 1000 when b ≥ 33.
        val lastFrame = env.barsAt(frameCount - 1)
        for (b in 33 until barCount) {
            assertEquals(0f, lastFrame[b], absoluteTolerance = 1e-6f, "frame=${frameCount - 1} bar=$b should be 0 (tBar >= clipMs)")
        }
    }

    @Test
    fun smoothing_damps_a_step_transition() {
        // Build a clip that's silent for the first 500ms then full-scale for
        // the second 500ms. With alpha=0.4 the bars at the step crossing
        // should ramp up gradually; with alpha=1.0 (no smoothing) they jump
        // immediately. Comparing the centre bar's frame-by-frame trajectory
        // gives a lower bound on what smoothing buys.
        val pcm = ShortArray(sampleRate)
        for (i in sampleRate / 2 until sampleRate) pcm[i] = Short.MAX_VALUE

        val smoothed = envelopeFor(pcm, smoothingAlpha = 0.4f)
        val unsmoothed = envelopeFor(pcm, smoothingAlpha = 1f)

        val centerBar = barCount / 2
        // Frame whose tCenter is near the edge of the burst (≈500ms).
        // tCenter at f = f*1000/30 — frame 15 = 500ms, frame 16 ≈ 533ms.
        val edgeFrame = 16
        val smoothedValue = smoothed.barsAt(edgeFrame)[centerBar]
        val unsmoothedValue = unsmoothed.barsAt(edgeFrame)[centerBar]

        assertTrue(
            unsmoothedValue > 0.5f,
            "sanity: unsmoothed envelope at the burst edge should be high (got $unsmoothedValue)",
        )
        assertTrue(
            smoothedValue < unsmoothedValue,
            "smoothed value ($smoothedValue) should be strictly below unsmoothed ($unsmoothedValue)",
        )
    }

    @Test
    fun barsAt_returns_a_defensive_copy() {
        val env = envelopeFor(secondOfFullScale())
        val a = env.barsAt(0)
        a[0] = -999f
        val b = env.barsAt(0)
        assertTrue(b[0] != -999f, "barsAt should return a fresh copy each call")
    }

    @Test
    fun barsAt_clamps_out_of_range_frame_indices() {
        val env = envelopeFor(secondOfSilence())
        // Negative and past-end indices clamp rather than throw — Media3's
        // overlay may be invoked for presentation timestamps slightly past
        // the clip end, and the SnippetExporter relies on that contract.
        val before = env.barsAt(-5)
        val first = env.barsAt(0)
        val after = env.barsAt(frameCount + 100)
        val last = env.barsAt(frameCount - 1)
        assertTrue(before.contentEquals(first), "barsAt(-5) should clamp to barsAt(0)")
        assertTrue(after.contentEquals(last), "barsAt(>frameCount) should clamp to barsAt(last)")
    }

    @Test
    fun rejects_invalid_inputs() {
        runCatching {
            AmplitudeEnvelopeBuilder.build(
                pcm = ShortArray(0),
                sampleRate = 0,
                frameCount = 1,
                barCount = 1,
            )
        }.also {
            assertTrue(it.isFailure, "sampleRate=0 should throw")
        }
        runCatching {
            AmplitudeEnvelopeBuilder.build(
                pcm = ShortArray(0),
                sampleRate = 44_100,
                frameCount = 0,
                barCount = 1,
            )
        }.also {
            assertTrue(it.isFailure, "frameCount=0 should throw")
        }
    }

    @Test
    fun empty_pcm_returns_zero_envelope_without_throwing() {
        // Caller should fail upstream when decode fails (hard-fail policy),
        // but if an empty array reaches the builder it must produce a valid
        // (silent) envelope rather than crash.
        val env =
            AmplitudeEnvelopeBuilder.build(
                pcm = ShortArray(0),
                sampleRate = 44_100,
                frameCount = 5,
                barCount = 8,
            )
        assertEquals(5, env.frameCount)
        assertEquals(8, env.barCount)
        for (f in 0 until 5) {
            for (v in env.barsAt(f)) assertEquals(0f, v)
        }
    }
}

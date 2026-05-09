// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/**
 * Produces a deterministic [WaveformSamples] envelope from a seed string.
 * Slice 4 uses this for both the editor preview (Compose Canvas) and the MP4
 * render overlay — the two surfaces always show the same waveform for a given
 * snippet because they both call this with `snippet.id`.
 *
 * The output is intentionally NOT a real audio amplitude extraction: that's
 * the deferred Slice 4.5 work. The visuals look like a plausible podcast
 * envelope (varying bars with smoothed transitions) without requiring a
 * MediaCodec decode step.
 */
class WaveformGenerator {
    fun generate(
        seed: String,
        barCount: Int = DEFAULT_BAR_COUNT,
    ): WaveformSamples {
        require(barCount > 0) { "barCount must be positive" }
        // 64-bit accumulator avoids the silent collision that
        // `String.hashCode()`'s 32-bit Int would expose for distinct snippet IDs.
        val seedLong = seed.fold(0L) { acc, c -> acc * 31L + c.code }
        val rand = Random(seedLong)
        // Step 1: raw uniform in [0.15, 1.0] — bias away from zero so no bar
        // disappears entirely.
        val raw = FloatArray(barCount) { 0.15f + rand.nextFloat() * 0.85f }
        // Step 2: 3-tap smoothing pass — a real envelope has correlated
        // neighbours, so a 3-tap moving average kills the white-noise look.
        val smoothed = FloatArray(barCount)
        for (i in 0 until barCount) {
            val l = if (i == 0) raw[i] else raw[i - 1]
            val r = if (i == barCount - 1) raw[i] else raw[i + 1]
            smoothed[i] = (l + raw[i] + r) / 3f
        }
        // Step 3: nudge any accidentally-equal adjacent values apart by 1%.
        // The pass is intentionally sequential — nudging smoothed[i] moves the
        // pair-comparison ceiling for the next iteration, which is fine because
        // the smoothing step already correlated neighbours; the nudge only needs
        // to break exact ties, not enforce a minimum gap globally. NUDGE is
        // 20× EPS so a single nudge always pushes the pair safely past EPS.
        for (i in 1 until barCount) {
            if (abs(smoothed[i] - smoothed[i - 1]) < EPS) {
                smoothed[i] = (smoothed[i] + NUDGE).coerceAtMost(1f)
            }
        }
        return WaveformSamples(smoothed)
    }

    /**
     * Returns a phase-modulated copy of [samples] for VU-meter-style animation in
     * the MP4 render. Each bar's amplitude oscillates around its base value
     * `base[i] * (0.5 + 0.5 * sin(2π * f * t + phase[i]))` where `phase[i]` is a
     * deterministic per-bar offset seeded from [seed] (so bars don't bounce in
     * lockstep — they stagger like a real VU). The animation is purely visual:
     * it isn't tied to actual audio amplitude, but it makes the rendered MP4 look
     * audio-reactive instead of frozen.
     *
     * Pure function: same inputs → same output. Safe to call from any thread.
     *
     * @param samples           Base samples produced by [generate].
     * @param seed              Same string used to seed [generate]; drives per-bar phase.
     * @param presentationTimeUs Microseconds since the start of the clip (Media3 contract).
     * @param frequencyHz       Modulation frequency. 6 Hz is the default — fast enough
     *                          to read as movement, slow enough to avoid strobing.
     */
    fun modulateAt(
        samples: WaveformSamples,
        seed: String,
        presentationTimeUs: Long,
        frequencyHz: Float = DEFAULT_FREQUENCY_HZ,
    ): WaveformSamples {
        val barCount = samples.bars.size
        if (barCount == 0) return samples
        val phaseSeed = seed.fold(0L) { acc, c -> acc * 31L + c.code } xor PHASE_SEED_SALT
        val phaseRand = Random(phaseSeed)
        val tSeconds = presentationTimeUs / 1_000_000.0
        val out = FloatArray(barCount)
        for (i in 0 until barCount) {
            val phase = phaseRand.nextFloat() * 2f * PI.toFloat()
            val omega = 2f * PI.toFloat() * frequencyHz * tSeconds.toFloat() + phase
            // 0.5 + 0.5 sin(...) ∈ [0, 1] — multiplying the base amplitude by this
            // keeps the result in [0, 1] without further clamping.
            val mod = 0.5f + 0.5f * sin(omega)
            out[i] = samples.bars[i] * mod
        }
        return WaveformSamples(out)
    }

    private companion object {
        const val DEFAULT_BAR_COUNT = 64
        const val EPS = 0.001f
        const val NUDGE = 0.02f // NUDGE >> EPS so one nudge clears the threshold
        const val DEFAULT_FREQUENCY_HZ = 6f

        // Salt the phase RNG with a non-zero constant so the phase seed differs
        // from the amplitude seed — otherwise bars would all hit their peaks at
        // amplitude-correlated moments instead of looking like an independent VU.
        const val PHASE_SEED_SALT = 0x5F3759DFL
    }
}

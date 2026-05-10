// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlin.math.abs
import kotlin.random.Random

/**
 * Produces a deterministic [WaveformSamples] envelope from a seed string.
 * The editor's static waveform widget consumes this — same seed always
 * produces the same bars, so the editor preview is stable across redraws.
 *
 * The MP4 render path no longer uses this: the bars in the rendered MP4
 * come from [AmplitudeEnvelope] (per-frame RMS over the source audio), so
 * the snippet visualises real amplitude rather than synthetic phase wiggle.
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

    private companion object {
        const val DEFAULT_BAR_COUNT = 64
        const val EPS = 0.001f
        const val NUDGE = 0.02f // NUDGE >> EPS so one nudge clears the threshold
    }
}

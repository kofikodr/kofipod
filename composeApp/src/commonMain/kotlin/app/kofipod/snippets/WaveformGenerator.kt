// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlin.math.abs
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
        val rand = Random(seed.hashCode())
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
        // Step 3: nudge any accidentally-equal adjacent values apart by 1%
        // so the smoothing-avoids-constant-runs invariant holds for any seed.
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
        const val NUDGE = 0.02f
    }
}

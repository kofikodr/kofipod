// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Per-frame, per-bar amplitude values for the rendered MP4 snippet's bars
 * overlay. Replaces the synthetic phase-modulated wiggle from
 * `WaveformGenerator.modulateAt(...)` with values derived from the actual
 * source audio: each `(frame, bar)` cell is the normalised RMS of a small
 * time slice of the clip.
 *
 * Stored row-major as a single `FloatArray` of size `frameCount * barCount`
 * so per-frame lookup during overlay rendering is one bounded array slice
 * instead of a 2D allocation.
 *
 * Immutable: [barsAt] returns a defensive copy so the consumer (the
 * overlay) can keep the array without aliasing the envelope.
 */
class AmplitudeEnvelope(
    val frameCount: Int,
    val barCount: Int,
    private val values: FloatArray,
) {
    init {
        require(frameCount > 0) { "frameCount must be positive (got $frameCount)" }
        require(barCount > 0) { "barCount must be positive (got $barCount)" }
        require(values.size == frameCount * barCount) {
            "values size ${values.size} does not match $frameCount * $barCount"
        }
    }

    fun barsAt(frameIdx: Int): FloatArray {
        val f = frameIdx.coerceIn(0, frameCount - 1)
        val out = FloatArray(barCount)
        // copyInto is KMP-safe; System.arraycopy would be JVM-only and the
        // commonMain detekt rule blocks java.* imports.
        values.copyInto(
            destination = out,
            destinationOffset = 0,
            startIndex = f * barCount,
            endIndex = f * barCount + barCount,
        )
        return out
    }
}

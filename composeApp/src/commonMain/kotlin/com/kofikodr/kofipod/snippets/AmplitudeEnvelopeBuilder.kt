// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

import kotlin.math.sqrt

/**
 * Builds an [AmplitudeEnvelope] from decoded mono PCM. Pure function — no
 * platform deps — so JVM unit tests cover the algorithm end-to-end without
 * Robolectric / a device.
 *
 * Visual model (user-locked decision): each frame's bars represent a window
 * of audio centred on the playhead, with bar `b` at frame `f` showing the
 * RMS of a slice centred on `tCenterMs(f) + (b - barCount/2) * sliceWidthMs`.
 * As `f` advances the slices scroll through the clip, so the bars dance
 * with the source audio like a music-video visualiser.
 */
internal object AmplitudeEnvelopeBuilder {
    fun build(
        pcm: ShortArray,
        sampleRate: Int,
        frameCount: Int,
        barCount: Int,
        sliceWidthMs: Int = DEFAULT_SLICE_WIDTH_MS,
        fps: Int = DEFAULT_FPS,
        smoothingAlpha: Float = DEFAULT_SMOOTHING_ALPHA,
    ): AmplitudeEnvelope {
        require(sampleRate > 0) { "sampleRate must be positive (got $sampleRate)" }
        require(frameCount > 0) { "frameCount must be positive (got $frameCount)" }
        require(barCount > 0) { "barCount must be positive (got $barCount)" }
        require(sliceWidthMs > 0) { "sliceWidthMs must be positive (got $sliceWidthMs)" }
        require(fps > 0) { "fps must be positive (got $fps)" }
        require(smoothingAlpha in 0f..1f) {
            "smoothingAlpha must be in [0,1] (got $smoothingAlpha)"
        }

        val total = frameCount * barCount
        val values = FloatArray(total)
        if (pcm.isEmpty()) return AmplitudeEnvelope(frameCount, barCount, values)

        val samplesPerSlice =
            ((sliceWidthMs.toLong() * sampleRate) / 1000L)
                .toInt()
                .coerceAtLeast(1)
        val clipMs = pcm.size.toLong() * 1000L / sampleRate.toLong()

        // Pass 1: find the loudest slice in the whole clip — used to normalise
        // bar values into [0, 1]. Walking in non-overlapping windows is cheap
        // (linear in pcm.size) and a good enough estimate of peak RMS.
        var clipMaxRms = 0f
        run {
            var start = 0
            while (start < pcm.size) {
                val end = (start + samplesPerSlice).coerceAtMost(pcm.size)
                val rms = rmsOf(pcm, start, end)
                if (rms > clipMaxRms) clipMaxRms = rms
                start = end
            }
        }
        if (clipMaxRms <= 0f) {
            // Silent clip (or all zeros) — return all-zeros envelope rather
            // than divide-by-zero. Hard-fail policy lives upstream in
            // PcmDecoder; here we just produce a valid silent envelope.
            return AmplitudeEnvelope(frameCount, barCount, values)
        }

        // Pass 2: raw per-(frame, bar) RMS, normalised by clipMaxRms.
        val raw = FloatArray(total)
        val halfBars = barCount / 2L
        for (f in 0 until frameCount) {
            val tCenterMs = (f.toLong() * 1000L) / fps.toLong()
            val frameBase = f * barCount
            for (b in 0 until barCount) {
                val tBarMs = tCenterMs + (b - halfBars) * sliceWidthMs.toLong()
                if (tBarMs < 0L || tBarMs >= clipMs) {
                    raw[frameBase + b] = 0f
                    continue
                }
                val centerSample = (tBarMs * sampleRate.toLong() / 1000L).toInt()
                val sliceStart = (centerSample - samplesPerSlice / 2).coerceAtLeast(0)
                val sliceEnd = (sliceStart + samplesPerSlice).coerceAtMost(pcm.size)
                val rms = rmsOf(pcm, sliceStart, sliceEnd)
                raw[frameBase + b] = (rms / clipMaxRms).coerceIn(0f, 1f)
            }
        }

        // Pass 3: per-bar single-pole low-pass across frames. Without this a
        // single-sample spike (e.g. a transient hit at the centre of a slice)
        // would make the bar twitch for one frame and snap back, which reads
        // as flicker on a 30 fps overlay.
        for (b in 0 until barCount) {
            var prev = raw[b] // frame 0
            values[b] = prev
            for (f in 1 until frameCount) {
                val idx = f * barCount + b
                val curr = raw[idx]
                val smoothed = prev + smoothingAlpha * (curr - prev)
                values[idx] = smoothed
                prev = smoothed
            }
        }

        return AmplitudeEnvelope(frameCount, barCount, values)
    }

    /**
     * RMS of `pcm[start, end)` using a Long accumulator. Each squared Short
     * sample fits in Int (Short.MAX_VALUE^2 = 2^30-ish) but a sum of millions
     * of them does not — Long avoids the silent overflow that would clip RMS
     * to zero on long slices. We promote `s` to Long *before* the multiply so
     * the safety property survives any future widening of pcm's element type.
     */
    private fun rmsOf(
        pcm: ShortArray,
        start: Int,
        end: Int,
    ): Float {
        val n = end - start
        if (n <= 0) return 0f
        var acc = 0L
        for (i in start until end) {
            val s = pcm[i].toLong()
            acc += s * s
        }
        return sqrt(acc.toDouble() / n).toFloat()
    }

    private const val DEFAULT_SLICE_WIDTH_MS = 50
    private const val DEFAULT_FPS = 30
    private const val DEFAULT_SMOOTHING_ALPHA = 0.4f
}

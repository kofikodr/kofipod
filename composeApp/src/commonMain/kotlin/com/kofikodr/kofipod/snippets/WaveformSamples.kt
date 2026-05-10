// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

/**
 * 64-bar amplitude envelope used by the editor waveform widget and the MP4
 * render's waveform-card overlay. Each bar is in `[0,1]`. Same seed always
 * produces the same samples so the editor and the rendered MP4 show the
 * identical visual.
 *
 * Slice 4 ships these as a deterministic placeholder seeded by `snippet.id`.
 * Real audio-amplitude extraction is the seam at [WaveformGenerator] — when
 * it lands in a later slice, the editor and the renderer change shape on the
 * same frame because both already consume `WaveformSamples`.
 */
data class WaveformSamples(val bars: FloatArray) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is WaveformSamples && bars.contentEquals(other.bars))

    override fun hashCode(): Int = bars.contentHashCode()
}

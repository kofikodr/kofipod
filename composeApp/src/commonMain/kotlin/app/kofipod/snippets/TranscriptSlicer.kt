// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlin.math.abs

/**
 * Picks a single ~one-line caption from a publisher transcript. Recognises
 * WebVTT (`HH:MM:SS.sss`) and SRT (`HH:MM:SS,sss`) cue formats; for plain
 * text, returns the first 200 chars as a coarse fallback. Returns null on
 * empty input — the caller renders without a caption overlay in that case.
 *
 * Slice 4 burns a single static caption into the MP4. Karaoke-timed reveal
 * is deferred — when it lands, this function will be replaced by a richer
 * cue-list slicer; the [String?] return shape stays.
 */
object TranscriptSlicer {
    private const val PLAIN_TEXT_LIMIT = 200

    fun sliceForWindow(
        transcript: String,
        startMs: Long,
        endMs: Long,
    ): String? {
        if (transcript.isBlank()) return null
        val cues = parseCues(transcript)
        if (cues.isEmpty()) {
            return transcript.trim().take(PLAIN_TEXT_LIMIT).ifBlank { null }
        }
        // Prefer a cue overlapping the window; else pick the cue with the
        // smallest distance from startMs.
        val overlapping = cues.firstOrNull { it.startMs in startMs..endMs || startMs in it.startMs..it.endMs }
        if (overlapping != null) return overlapping.text
        return cues.minByOrNull { abs(it.startMs - startMs) }?.text
    }

    private data class Cue(val startMs: Long, val endMs: Long, val text: String)

    private val CUE_LINE =
        Regex(
            """(\d{2}):(\d{2}):(\d{2})[.,](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[.,](\d{3})""",
        )

    private fun parseCues(transcript: String): List<Cue> {
        val lines = transcript.lines()
        val out = mutableListOf<Cue>()
        var i = 0
        while (i < lines.size) {
            val m = CUE_LINE.matchEntire(lines[i].trim())
            if (m != null) {
                val (h1, m1, s1, ms1, h2, m2, s2, ms2) = m.destructured
                val startMs = h1.toLong() * 3_600_000 + m1.toLong() * 60_000 + s1.toLong() * 1_000 + ms1.toLong()
                val endMs = h2.toLong() * 3_600_000 + m2.toLong() * 60_000 + s2.toLong() * 1_000 + ms2.toLong()
                val textLines = mutableListOf<String>()
                var j = i + 1
                while (j < lines.size && lines[j].isNotBlank()) {
                    textLines.add(lines[j].trim())
                    j++
                }
                val text = textLines.joinToString(" ").trim()
                if (text.isNotEmpty()) out.add(Cue(startMs, endMs, text))
                i = j
            } else {
                i++
            }
        }
        return out
    }
}

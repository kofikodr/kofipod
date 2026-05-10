// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

/**
 * Prompt copy for the caption-fallback path that hits Gemini when no
 * publisher transcript is available. Centralised so a future tuning pass
 * (or model upgrade) is a one-line change.
 */
object SnippetCaptionPrompts {
    fun transcriptionPrompt(
        startMs: Long,
        endMs: Long,
    ): String {
        val window = "${startMs / 1_000}s..${endMs / 1_000}s"
        return """
            Transcribe the audio between $window into one short caption (max 25 words).
            Return only the spoken words. No timestamps, no speaker labels, no quotation marks.
            If the segment is silent or unintelligible, return an empty string.
            """.trimIndent()
    }
}

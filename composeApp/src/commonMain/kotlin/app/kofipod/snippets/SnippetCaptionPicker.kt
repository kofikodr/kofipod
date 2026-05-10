// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Pure-Kotlin path picker mirroring [app.kofipod.ai.AiSummaryRepository]'s
 * transcript-vs-audio decision. Isolated for unit testability — production
 * is one [pick] call inside [SnippetCaptionRepository.resolveFor].
 */
class SnippetCaptionPicker {
    enum class Path { Transcript, Gemini, None }

    fun pick(
        transcriptUrl: String?,
        isAudioDownloaded: Boolean,
        hasGeminiKey: Boolean,
    ): Path {
        if (!transcriptUrl.isNullOrBlank()) return Path.Transcript
        if (isAudioDownloaded && hasGeminiKey) return Path.Gemini
        return Path.None
    }
}

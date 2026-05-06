// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Output of [SnippetCaptionRepository.resolveFor]. The render service
 * consumes this and only burns text into the MP4 when [FromTranscript] /
 * [FromGemini] is returned. [None] is informational; rendering proceeds
 * without a caption overlay.
 */
sealed interface CaptionResolution {
    data class FromTranscript(val text: String) : CaptionResolution

    data class FromGemini(val text: String) : CaptionResolution

    data class None(val reason: NoneReason) : CaptionResolution
}

enum class NoneReason {
    NoTranscript,
    NoAudioDownloaded,
    NoGeminiKey,
    GeminiFailed,
}

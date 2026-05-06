// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import app.kofipod.ai.TranscriptFetcher
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.db.Episode
import kotlinx.coroutines.flow.firstOrNull

/**
 * Resolves a single caption string for a snippet, preferring publisher
 * transcript over Gemini transcription. Mirrors the path picker in
 * [app.kofipod.ai.AiSummaryRepository] but produces a single line, not a
 * structured Summary JSON.
 *
 * Caller (the render service) consumes [CaptionResolution] and either burns
 * the text into the MP4 overlay or proceeds without a caption.
 */
class SnippetCaptionRepository(
    private val episodes: EpisodeSource,
    private val transcripts: TranscriptFetcher,
    private val deps: CaptionDeps,
    private val picker: SnippetCaptionPicker = SnippetCaptionPicker(),
) {
    suspend fun resolveFor(snippet: Snippet): CaptionResolution {
        // NoTranscript is used as a proxy for "episode not found" — NoneReason has no
        // dedicated NoEpisode variant yet. Reason is diagnostics-only (no UI surface in v1.0).
        val episode =
            episodes.episodeFlow(snippet.episodeId).firstOrNull()
                ?: return CaptionResolution.None(CaptionResolution.NoneReason.NoTranscript)
        val isAudioReady = deps.isAudioReadyFor(snippet.episodeId)
        val key = deps.currentGeminiKey()

        val path =
            picker.pick(
                transcriptUrl = episode.transcriptUrl,
                isAudioDownloaded = isAudioReady,
                hasGeminiKey = !key.isNullOrBlank(),
            )

        return when (path) {
            SnippetCaptionPicker.Path.Transcript -> resolveFromTranscript(episode, snippet)
            SnippetCaptionPicker.Path.Gemini -> resolveFromGemini(snippet)
            SnippetCaptionPicker.Path.None -> {
                val reason =
                    when {
                        !isAudioReady -> CaptionResolution.NoneReason.NoAudioDownloaded
                        key.isNullOrBlank() -> CaptionResolution.NoneReason.NoGeminiKey
                        else -> CaptionResolution.NoneReason.NoTranscript
                    }
                CaptionResolution.None(reason)
            }
        }
    }

    private suspend fun resolveFromTranscript(
        episode: Episode,
        snippet: Snippet,
    ): CaptionResolution {
        val url =
            episode.transcriptUrl
                ?: return CaptionResolution.None(CaptionResolution.NoneReason.NoTranscript)
        val text =
            transcripts.fetch(url).getOrElse {
                return CaptionResolution.None(CaptionResolution.NoneReason.NoTranscript)
            }
        val sliced =
            TranscriptSlicer.sliceForWindow(text, snippet.startMs, snippet.endMs)
                ?: return CaptionResolution.None(CaptionResolution.NoneReason.NoTranscript)
        return CaptionResolution.FromTranscript(sliced)
    }

    private suspend fun resolveFromGemini(snippet: Snippet): CaptionResolution {
        val prompt = SnippetCaptionPrompts.transcriptionPrompt(snippet.startMs, snippet.endMs)
        val text =
            deps.transcribeForCaption(snippet.episodeId, prompt).getOrElse {
                return CaptionResolution.None(CaptionResolution.NoneReason.GeminiFailed)
            }
        if (text.isBlank()) return CaptionResolution.None(CaptionResolution.NoneReason.GeminiFailed)
        return CaptionResolution.FromGemini(text.trim())
    }
}

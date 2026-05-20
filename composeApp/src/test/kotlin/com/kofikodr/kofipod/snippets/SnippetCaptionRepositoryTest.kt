// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

import com.kofikodr.kofipod.ai.TranscriptFetcher
import com.kofikodr.kofipod.data.repo.EpisodeSource
import com.kofikodr.kofipod.data.repo.RefreshResult
import com.kofikodr.kofipod.db.Episode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SnippetCaptionRepositoryTest {
    @Test
    fun transcript_path_returns_FromTranscript() =
        runTest {
            val repo =
                makeRepoWith(
                    transcriptUrl = "https://x.com/t.vtt",
                    transcriptBody =
                        """
                        WEBVTT

                        00:00:10.000 --> 00:00:15.000
                        Bazel inflection.
                        """.trimIndent(),
                    audioDownloaded = false,
                    geminiKey = null,
                )
            val r = repo.resolveFor(snippet(startMs = 10_000, endMs = 15_000))
            assertTrue(r is CaptionResolution.FromTranscript)
            assertEquals("Bazel inflection.", r.text)
        }

    @Test
    fun no_transcript_audio_and_key_present_uses_gemini() =
        runTest {
            // Recording fake captures the request so we can verify the repo
            // hands Gemini the SNIPPET's episode ID and a prompt naming the
            // exact start/end window. The prior assertion only checked the
            // returned text — a regression that transcribed the wrong episode
            // (e.g. cached the previous snippet's episodeId) or the wrong
            // time window (e.g. used 0..fullEpisode) would still produce
            // success and pass the test.
            val captures = mutableListOf<Pair<String, String>>()
            val repo =
                makeRepoWith(
                    transcriptUrl = null,
                    transcriptBody = "",
                    audioDownloaded = true,
                    geminiKey = "k",
                    geminiResponse = "Audio caption from Gemini.",
                    recordTo = captures,
                )
            val r = repo.resolveFor(snippet(startMs = 10_000L, endMs = 15_000L))
            assertTrue(r is CaptionResolution.FromGemini)
            assertEquals("Audio caption from Gemini.", r.text)

            assertEquals(1, captures.size, "Exactly one Gemini call must fire for one snippet")
            val (calledEpisodeId, calledPrompt) = captures.single()
            assertEquals("ep-1", calledEpisodeId, "Must transcribe the snippet's episode")
            // Window naming: prompt must reference the requested start/end so
            // a refactor that drops the window from the prompt body is caught.
            // The repo formats seconds as "<start>s..<end>s" — 10_000..15_000 ms
            // is the standard window for these tests.
            assertTrue(
                calledPrompt.contains("10s") && calledPrompt.contains("15s"),
                "Prompt must name the snippet window in seconds; got: $calledPrompt",
            )
        }

    @Test
    fun no_transcript_no_audio_returns_None_NoAudioDownloaded() =
        runTest {
            val repo =
                makeRepoWith(
                    transcriptUrl = null,
                    transcriptBody = "",
                    audioDownloaded = false,
                    geminiKey = "k",
                )
            val r = repo.resolveFor(snippet())
            assertTrue(r is CaptionResolution.None)
            assertEquals(CaptionResolution.NoneReason.NoAudioDownloaded, r.reason)
        }

    @Test
    fun no_transcript_audio_but_no_key_returns_None_NoGeminiKey() =
        runTest {
            val repo =
                makeRepoWith(
                    transcriptUrl = null,
                    transcriptBody = "",
                    audioDownloaded = true,
                    geminiKey = null,
                )
            val r = repo.resolveFor(snippet())
            assertTrue(r is CaptionResolution.None)
            assertEquals(CaptionResolution.NoneReason.NoGeminiKey, r.reason)
        }

    @Test
    fun gemini_failure_returns_None_GeminiFailed() =
        runTest {
            val repo =
                makeRepoWith(
                    transcriptUrl = null,
                    transcriptBody = "",
                    audioDownloaded = true,
                    geminiKey = "k",
                    // null geminiResponse forces a failure result from the fake
                    geminiResponse = null,
                )
            val r = repo.resolveFor(snippet())
            assertTrue(r is CaptionResolution.None)
            assertEquals(CaptionResolution.NoneReason.GeminiFailed, r.reason)
        }

    @Test
    fun gemini_blank_response_treated_as_failure() =
        runTest {
            // Empty/blank Gemini output is not a useful caption — fall through to None.
            val repo =
                makeRepoWith(
                    transcriptUrl = null,
                    transcriptBody = "",
                    audioDownloaded = true,
                    geminiKey = "k",
                    geminiResponse = "   ",
                )
            val r = repo.resolveFor(snippet())
            assertTrue(r is CaptionResolution.None)
            assertEquals(CaptionResolution.NoneReason.GeminiFailed, r.reason)
        }

    @Test
    fun episode_not_found_returns_None() =
        runTest {
            // Simulates a deleted or orphaned snippet whose episode no longer exists.
            val repo =
                makeRepoWith(transcriptUrl = null, transcriptBody = "", audioDownloaded = false, geminiKey = null, episodeExists = false)
            val r = repo.resolveFor(snippet())
            assertTrue(r is CaptionResolution.None)
        }

    // Helpers -------------------------------------------------------------
    private fun snippet(
        startMs: Long = 0L,
        endMs: Long = 60_000L,
    ) = Snippet(
        id = "snip-test", episodeId = "ep-1", podcastId = "pc-1",
        startMs = startMs, endMs = endMs, title = null, captionOverride = null,
        createdAtMs = 1_000L, lastExportFormat = null, lastExportPath = null,
    )

    /**
     * Builds a `SnippetCaptionRepository` with three small fakes:
     * - A fake `EpisodeSource` that emits a single Episode with the given transcriptUrl,
     *   or null when [episodeExists] is false (simulates a deleted/orphaned episode).
     * - A fake `TranscriptFetcher` (`fun interface`) that returns `transcriptBody` if non-blank.
     * - A fake `CaptionDeps` parameterised by audioDownloaded / geminiKey / geminiResponse.
     */
    private fun makeRepoWith(
        transcriptUrl: String?,
        transcriptBody: String,
        audioDownloaded: Boolean,
        geminiKey: String?,
        geminiResponse: String? = "ok",
        episodeExists: Boolean = true,
        recordTo: MutableList<Pair<String, String>>? = null,
    ): SnippetCaptionRepository {
        val episode =
            Episode(
                id = "ep-1",
                podcastId = "pc-1",
                guid = "g",
                title = "T",
                description = "",
                publishedAt = 0L,
                durationSec = 60L,
                enclosureUrl = "https://x/audio.mp3",
                enclosureMimeType = "audio/mpeg",
                fileSizeBytes = 1_000_000L,
                seasonNumber = null,
                episodeNumber = null,
                // imageUrl is NOT NULL with DEFAULT '' in Episode.sq
                imageUrl = "",
                chaptersUrl = null,
                transcriptUrl = transcriptUrl,
            )
        val episodes =
            object : EpisodeSource {
                override fun episodesFlow(podcastId: String): Flow<List<Episode>> = flowOf(listOf(episode))

                override fun episodeFlow(episodeId: String): Flow<Episode?> = if (episodeExists) flowOf(episode) else flowOf(null)

                override fun newEpisodeCountsFlow(): Flow<Map<String, Int>> = flowOf(emptyMap())

                override suspend fun refresh(
                    podcastId: String,
                    feedId: Long,
                    nowMillis: Long,
                ): RefreshResult = error("refresh() should not be called from SnippetCaptionRepository")
            }
        val transcripts =
            TranscriptFetcher { _ ->
                if (transcriptBody.isNotBlank()) {
                    Result.success(transcriptBody)
                } else {
                    Result.failure(IllegalStateException("empty transcript"))
                }
            }
        val deps =
            object : CaptionDeps {
                override suspend fun isAudioReadyFor(episodeId: String): Boolean = audioDownloaded

                override suspend fun currentGeminiKey(): String? = geminiKey

                override suspend fun transcribeForCaption(
                    episodeId: String,
                    prompt: String,
                ): Result<String> {
                    recordTo?.add(episodeId to prompt)
                    return if (geminiResponse != null) {
                        Result.success(geminiResponse)
                    } else {
                        Result.failure(IllegalStateException("gemini failed"))
                    }
                }
            }
        return SnippetCaptionRepository(episodes, transcripts, deps)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import app.kofipod.db.Download
import app.kofipod.db.Episode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Pins [AudioDiscussSource]'s download-eligibility check. A bug here either
 * (a) gates Discuss off for episodes that ARE discussable, breaking the
 * audio fallback we just shipped, or (b) opens Discuss against a partial
 * download — which the coordinator would then upload and Gemini would reject
 * with a 400 INVALID_ARGUMENT.
 */
class AudioDiscussSourceTest {
    @Test
    fun loadContext_returnsAudioReady_whenDownloadCompleted() =
        runTest {
            val source = AudioDiscussSource()
            val download =
                downloadOf("ep1", state = "Completed", localPath = "/tmp/ep1.mp3", bytes = 12_345L)

            val ctx = source.loadContext(episodeOf("ep1", mime = "audio/x-m4a"), download).getOrThrow()

            val ready = assertIs<DiscussContext.AudioReady>(ctx)
            assertEquals("/tmp/ep1.mp3", ready.localPath)
            assertEquals("audio/x-m4a", ready.mimeType)
            assertEquals(12_345L, ready.sizeBytes)
            assertEquals("12345", ready.fingerprint, "Fingerprint must mirror downloadedBytes — drives cache validity")
        }

    @Test
    fun loadContext_returnsNotAvailable_whenDownloadIsNull() =
        runTest {
            val source = AudioDiscussSource()
            val ctx = source.loadContext(episodeOf("ep1"), download = null).getOrThrow()
            assertEquals(DiscussContext.NotAvailable, ctx)
        }

    @Test
    fun loadContext_returnsNotAvailable_whenDownloadIsPartial() =
        runTest {
            // Partial / paused / errored downloads aren't chat-able — Gemini
            // would receive a truncated file. Mirrors AiSummaryRepository's
            // pickSource gate.
            val source = AudioDiscussSource()
            val download =
                downloadOf("ep1", state = "Downloading", localPath = "/tmp/ep1.partial", bytes = 5_000L)

            val ctx = source.loadContext(episodeOf("ep1"), download).getOrThrow()

            assertEquals(DiscussContext.NotAvailable, ctx)
        }

    @Test
    fun loadContext_returnsNotAvailable_whenLocalPathBlank() =
        runTest {
            // A "Completed" row without a local path is structurally impossible
            // in production (DownloadRepository sets the path when transitioning
            // to Completed) but defending the gate here keeps the coordinator
            // from being asked to open a non-file path.
            val source = AudioDiscussSource()
            val download = downloadOf("ep1", state = "Completed", localPath = "", bytes = 12_345L)

            val ctx = source.loadContext(episodeOf("ep1"), download).getOrThrow()

            assertEquals(DiscussContext.NotAvailable, ctx)
        }

    @Test
    fun loadContext_fallsBackToDefaultMime_whenEpisodeMimeIsBlank() =
        runTest {
            // Some misconfigured RSS feeds omit enclosure type. We default to
            // audio/mpeg so the upload still has a plausible Content-Type.
            val source = AudioDiscussSource()
            val download = downloadOf("ep1", state = "Completed", localPath = "/tmp/x.mp3", bytes = 100L)

            val ctx = source.loadContext(episodeOf("ep1", mime = ""), download).getOrThrow()

            val ready = assertIs<DiscussContext.AudioReady>(ctx)
            assertEquals("audio/mpeg", ready.mimeType)
        }

    private fun episodeOf(
        id: String,
        mime: String = "audio/mpeg",
    ): Episode =
        Episode(
            id = id,
            podcastId = "pod1",
            guid = "g-$id",
            title = "T",
            description = "",
            publishedAt = 0L,
            durationSec = 600L,
            enclosureUrl = "https://example.com/audio.mp3",
            enclosureMimeType = mime,
            fileSizeBytes = 0L,
            seasonNumber = null,
            episodeNumber = null,
            imageUrl = "",
            chaptersUrl = null,
            transcriptUrl = null,
        )

    private fun downloadOf(
        episodeId: String,
        state: String,
        localPath: String,
        bytes: Long,
    ): Download =
        Download(
            episodeId = episodeId,
            state = state,
            localPath = localPath,
            downloadedBytes = bytes,
            totalBytes = bytes,
            source = "manual",
            startedAt = 0L,
            completedAt = 0L.takeIf { state == "Completed" },
            errorMessage = null,
        )
}

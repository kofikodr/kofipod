// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import app.kofipod.ai.AiSourceKind
import app.kofipod.ai.AiSummary
import app.kofipod.bookmarks.Bookmark
import app.kofipod.db.Episode
import app.kofipod.db.Podcast
import app.kofipod.snippets.Snippet
import app.kofipod.snippets.SnippetFormat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coordinator-level behaviour for [PkmExportCoordinator]. Verifies the pipeline
 * for each request kind, the not-found short-circuit branches, the Throwable
 * collapse on sink failure, and that [PkmExportCoordinator.dismiss] is a pure
 * sheet-clear (never emits a result).
 *
 * Coroutine plumbing: each test builds a [CoroutineScope] over an
 * [UnconfinedTestDispatcher] tied to the surrounding [TestScope]'s scheduler
 * and uses it as the coordinator's `appScope`. The "unconfined" dispatcher
 * runs `appScope.launch { ... }` bodies inline on the calling thread, which
 * collapses the launch+await ceremony into deterministic sequential code while
 * still keeping the test under the [TestScope] umbrella (no real-thread
 * sleeps). The coordinator's [PkmExportCoordinator.results] SharedFlow is
 * configured with `replay = 1` so the most recent emission is still readable
 * via `replayCache` after the launch returns.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PkmExportCoordinatorTest {
    @Test
    fun snippetClipboardSucceedsAndClearsSheet() =
        runTest {
            val sink = FakeSink()
            val coord =
                newCoordinator(
                    deps =
                        FakeDeps(
                            snippet = sampleSnippet(),
                            episode = sampleEpisode(),
                            podcast = samplePodcast(),
                        ),
                    sink = sink,
                )

            coord.show(PkmExportRequest.Snippet("snip-1"))
            assertEquals(PkmExportRequest.Snippet("snip-1"), coord.pendingRequest.value)

            coord.execute(PkmExportRequest.Snippet("snip-1"), PkmExportSink.Clipboard)

            assertEquals(1, sink.clipboardCalls)
            assertEquals(0, sink.fileCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(PkmExportResult.Copied, coord.results.replayCache.firstOrNull())
        }

    @Test
    fun bookmarkFileSucceedsAndClearsSheet() =
        runTest {
            val sink = FakeSink()
            val coord =
                newCoordinator(
                    deps =
                        FakeDeps(
                            bookmark = sampleBookmark(),
                            episode = sampleEpisode(),
                            podcast = samplePodcast(),
                        ),
                    sink = sink,
                )

            coord.show(PkmExportRequest.Bookmark("bm-1"))
            coord.execute(PkmExportRequest.Bookmark("bm-1"), PkmExportSink.File)

            assertEquals(0, sink.clipboardCalls)
            assertEquals(1, sink.fileCalls)
            assertEquals("Share Markdown", sink.lastShareTitle)
            assertNull(coord.pendingRequest.value)
            assertEquals(PkmExportResult.Shared, coord.results.replayCache.firstOrNull())
        }

    @Test
    fun aiSummaryClipboardSucceedsAndClearsSheet() =
        runTest {
            val sink = FakeSink()
            val coord =
                newCoordinator(
                    deps =
                        FakeDeps(
                            summary = sampleSummary(),
                            episode = sampleEpisode(),
                            podcast = samplePodcast(),
                        ),
                    sink = sink,
                )

            coord.show(PkmExportRequest.AiSummary("e1"))
            coord.execute(PkmExportRequest.AiSummary("e1"), PkmExportSink.Clipboard)

            assertEquals(1, sink.clipboardCalls)
            assertEquals(0, sink.fileCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(PkmExportResult.Copied, coord.results.replayCache.firstOrNull())
        }

    @Test
    fun missingSnippetEmitsItemNotFoundAndClearsSheet() =
        runTest {
            // snippet = null -> snippetById returns null -> buildDocument short-circuits.
            val sink = FakeSink()
            val coord =
                newCoordinator(
                    deps = FakeDeps(snippet = null, episode = sampleEpisode(), podcast = samplePodcast()),
                    sink = sink,
                )

            coord.show(PkmExportRequest.Snippet("missing"))
            coord.execute(PkmExportRequest.Snippet("missing"), PkmExportSink.Clipboard)

            assertEquals(0, sink.clipboardCalls)
            assertEquals(0, sink.fileCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(
                PkmExportResult.Failed("Item not found"),
                coord.results.replayCache.firstOrNull(),
            )
        }

    @Test
    fun missingEpisodeEmitsItemNotFoundAndClearsSheet() =
        runTest {
            // Snippet resolves but its referenced episode does not.
            val sink = FakeSink()
            val coord =
                newCoordinator(
                    deps = FakeDeps(snippet = sampleSnippet(), episode = null, podcast = samplePodcast()),
                    sink = sink,
                )

            coord.show(PkmExportRequest.Snippet("snip-1"))
            coord.execute(PkmExportRequest.Snippet("snip-1"), PkmExportSink.Clipboard)

            assertEquals(0, sink.clipboardCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(
                PkmExportResult.Failed("Item not found"),
                coord.results.replayCache.firstOrNull(),
            )
        }

    @Test
    fun missingPodcastEmitsItemNotFoundAndClearsSheet() =
        runTest {
            // Snippet + episode resolve but the podcast row is gone.
            val sink = FakeSink()
            val coord =
                newCoordinator(
                    deps = FakeDeps(snippet = sampleSnippet(), episode = sampleEpisode(), podcast = null),
                    sink = sink,
                )

            coord.show(PkmExportRequest.Snippet("snip-1"))
            coord.execute(PkmExportRequest.Snippet("snip-1"), PkmExportSink.Clipboard)

            assertEquals(0, sink.clipboardCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(
                PkmExportResult.Failed("Item not found"),
                coord.results.replayCache.firstOrNull(),
            )
        }

    @Test
    fun sinkExceptionCollapsesIntoFailedWithMessageAndClearsSheet() =
        runTest {
            val sink =
                FakeSink().apply {
                    throwOnClipboard = IllegalStateException("clipboard exploded")
                }
            val coord =
                newCoordinator(
                    deps =
                        FakeDeps(
                            snippet = sampleSnippet(),
                            episode = sampleEpisode(),
                            podcast = samplePodcast(),
                        ),
                    sink = sink,
                )

            coord.show(PkmExportRequest.Snippet("snip-1"))
            coord.execute(PkmExportRequest.Snippet("snip-1"), PkmExportSink.Clipboard)

            // The sink was reached (so the path entered the dispatch arm),
            // but the thrown exception is collapsed into a Failed result, the
            // sheet is cleared, and the message surfaces what the exception
            // said.
            assertNull(coord.pendingRequest.value)
            val result = coord.results.replayCache.firstOrNull()
            assertTrue(result is PkmExportResult.Failed, "expected Failed but was $result")
            assertEquals("clipboard exploded", result.message)
        }

    @Test
    fun dismissClearsPendingRequestWithoutEmittingResult() =
        runTest {
            val sink = FakeSink()
            val coord =
                newCoordinator(
                    deps =
                        FakeDeps(
                            snippet = sampleSnippet(),
                            episode = sampleEpisode(),
                            podcast = samplePodcast(),
                        ),
                    sink = sink,
                )

            coord.show(PkmExportRequest.Snippet("snip-1"))
            coord.dismiss()

            assertEquals(0, sink.clipboardCalls)
            assertEquals(0, sink.fileCalls)
            assertNull(coord.pendingRequest.value)
            // dismiss MUST be a pure UI clear -- no toast / snackbar / error.
            assertNull(coord.results.replayCache.firstOrNull())
        }

    // -------------------------------------------------------------------------
    // Test plumbing
    // -------------------------------------------------------------------------

    /**
     * Builds a coordinator wired to a [TestScope]-bound [UnconfinedTestDispatcher].
     * Using `Unconfined` makes the `appScope.launch { ... }` body run inline on
     * the test thread, so post-`execute()` assertions don't need an explicit
     * `advanceUntilIdle`. Stays inside `TestScope` so failures don't leak as
     * uncaught.
     */
    private fun TestScope.newCoordinator(
        deps: PkmExportDeps,
        sink: MarkdownSink,
        formatter: MarkdownFormatter = MarkdownFormatterImpl(),
    ): PkmExportCoordinator {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return PkmExportCoordinator(
            deps = deps,
            formatter = formatter,
            sink = sink,
            appScope = CoroutineScope(dispatcher),
        )
    }

    // -------------------------------------------------------------------------
    // Test doubles
    // -------------------------------------------------------------------------

    private class FakeDeps(
        val snippet: Snippet? = null,
        val bookmark: Bookmark? = null,
        val summary: AiSummary? = null,
        val episode: Episode? = null,
        val podcast: Podcast? = null,
    ) : PkmExportDeps {
        override suspend fun snippetById(id: String): Snippet? = snippet

        override suspend fun bookmarkById(id: String): Bookmark? = bookmark

        override suspend fun summaryFor(episodeId: String): AiSummary? = summary

        override fun episode(id: String): Episode? = episode

        override fun podcast(id: String): Podcast? = podcast
    }

    private class FakeSink : MarkdownSink {
        var clipboardCalls = 0
        var fileCalls = 0
        var lastShareTitle: String? = null
        var throwOnClipboard: Throwable? = null
        var throwOnFile: Throwable? = null

        override fun exportToClipboard(document: MarkdownDocument) {
            throwOnClipboard?.let { throw it }
            clipboardCalls += 1
        }

        override suspend fun exportAsFile(
            document: MarkdownDocument,
            shareTitle: String,
        ) {
            throwOnFile?.let { throw it }
            fileCalls += 1
            lastShareTitle = shareTitle
        }
    }

    // -------------------------------------------------------------------------
    // Sample fixtures -- field shapes mirror MarkdownFormatterTest's so the
    // formatter does not throw on any of these values.
    // -------------------------------------------------------------------------

    private fun samplePodcast() =
        Podcast(
            id = "p1",
            title = "Locked On Broncos",
            author = "",
            description = "",
            artworkUrl = "",
            feedUrl = "https://example.com/feed",
            listId = null,
            autoDownloadEnabled = 0,
            notifyNewEpisodesEnabled = 0,
            lastCheckedAt = null,
            addedAt = 0,
            primaryCategory = "",
        )

    private fun sampleEpisode() =
        Episode(
            id = "e1",
            podcastId = "p1",
            guid = "g1",
            title = "FCC bans routers",
            description = "",
            publishedAt = 0,
            durationSec = 0,
            enclosureUrl = "https://example.com/ep1.mp3",
            enclosureMimeType = "audio/mpeg",
            fileSizeBytes = 0,
            seasonNumber = null,
            episodeNumber = null,
            imageUrl = "",
            chaptersUrl = null,
            transcriptUrl = null,
        )

    private fun sampleSnippet() =
        Snippet(
            id = "snip-1",
            episodeId = "e1",
            podcastId = "p1",
            startMs = 0,
            endMs = 60_000,
            title = "Take",
            captionOverride = null,
            createdAtMs = 0,
            lastExportFormat = SnippetFormat.MP4,
            lastExportPath = null,
        )

    private fun sampleBookmark() =
        Bookmark(
            id = "bm-1",
            episodeId = "e1",
            podcastId = "p1",
            timestampMs = 0,
            note = null,
            createdAtMs = 0,
        )

    private fun sampleSummary() =
        AiSummary(
            episodeId = "e1",
            generatedAtMs = 0,
            modelId = "gemini-1.5-flash",
            sourceKind = AiSourceKind.Transcript,
            sourceFingerprint = "fp",
            summary = "Body",
        )
}

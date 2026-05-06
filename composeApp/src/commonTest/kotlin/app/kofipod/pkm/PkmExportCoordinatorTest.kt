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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
 * sleeps).
 *
 * Result observation: the coordinator's [PkmExportCoordinator.results] is now
 * `replay = 0` (so a re-subscribed snackbar host doesn't re-toast a stale
 * result). Tests therefore start a [TestScope.backgroundScope] collector
 * before calling `execute(...)` and assert against the captured list. Using
 * `backgroundScope` is the recommended pattern for collecting hot flows under
 * `runTest` because it auto-cancels at the end of the test.
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
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.Snippet("snip-1"))
            assertEquals(PkmExportRequest.Snippet("snip-1"), coord.pendingRequest.value)

            coord.execute(PkmExportRequest.Snippet("snip-1"), PkmExportSink.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(1, sink.clipboardCalls)
            assertEquals(0, sink.fileCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Copied), received.toList())
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
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.Bookmark("bm-1"))
            coord.execute(PkmExportRequest.Bookmark("bm-1"), PkmExportSink.File)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, sink.clipboardCalls)
            assertEquals(1, sink.fileCalls)
            assertEquals("Share Markdown", sink.lastShareTitle)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Shared), received.toList())
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
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.AiSummary("e1"))
            coord.execute(PkmExportRequest.AiSummary("e1"), PkmExportSink.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(1, sink.clipboardCalls)
            assertEquals(0, sink.fileCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Copied), received.toList())
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
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.Snippet("missing"))
            coord.execute(PkmExportRequest.Snippet("missing"), PkmExportSink.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, sink.clipboardCalls)
            assertEquals(0, sink.fileCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Failed("Item not found")), received.toList())
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
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.Snippet("snip-1"))
            coord.execute(PkmExportRequest.Snippet("snip-1"), PkmExportSink.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, sink.clipboardCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Failed("Item not found")), received.toList())
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
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.Snippet("snip-1"))
            coord.execute(PkmExportRequest.Snippet("snip-1"), PkmExportSink.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, sink.clipboardCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Failed("Item not found")), received.toList())
        }

    @Test
    fun aiSummaryNotFoundEmitsFailed() =
        runTest {
            // summary = null -> summaryFor returns null -> buildDocument short-circuits
            // on the AiSummary branch before any episode/podcast lookup.
            val sink = FakeSink()
            val coord =
                newCoordinator(
                    deps = FakeDeps(summary = null, episode = sampleEpisode(), podcast = samplePodcast()),
                    sink = sink,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.AiSummary("e1"))
            coord.execute(PkmExportRequest.AiSummary("e1"), PkmExportSink.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, sink.clipboardCalls)
            assertEquals(0, sink.fileCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Failed("Item not found")), received.toList())
        }

    @Test
    fun aiSummaryEpisodeMissingEmitsFailed() =
        runTest {
            // Summary resolves but the referenced episode row is gone.
            val sink = FakeSink()
            val coord =
                newCoordinator(
                    deps = FakeDeps(summary = sampleSummary(), episode = null, podcast = samplePodcast()),
                    sink = sink,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.AiSummary("e1"))
            coord.execute(PkmExportRequest.AiSummary("e1"), PkmExportSink.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, sink.clipboardCalls)
            assertEquals(0, sink.fileCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Failed("Item not found")), received.toList())
        }

    @Test
    fun aiSummaryPodcastMissingEmitsFailed() =
        runTest {
            // Summary + episode resolve but the podcast row is gone.
            val sink = FakeSink()
            val coord =
                newCoordinator(
                    deps = FakeDeps(summary = sampleSummary(), episode = sampleEpisode(), podcast = null),
                    sink = sink,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.AiSummary("e1"))
            coord.execute(PkmExportRequest.AiSummary("e1"), PkmExportSink.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, sink.clipboardCalls)
            assertEquals(0, sink.fileCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Failed("Item not found")), received.toList())
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
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.Snippet("snip-1"))
            coord.execute(PkmExportRequest.Snippet("snip-1"), PkmExportSink.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            // The sink was reached (so the path entered the dispatch arm),
            // but the thrown exception is collapsed into a Failed result, the
            // sheet is cleared, and the message surfaces what the exception
            // said.
            assertNull(coord.pendingRequest.value)
            assertEquals(1, received.size)
            val result = received.single()
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
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.Snippet("snip-1"))
            coord.dismiss()
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, sink.clipboardCalls)
            assertEquals(0, sink.fileCalls)
            assertNull(coord.pendingRequest.value)
            // dismiss MUST be a pure UI clear -- no toast / snackbar / error.
            assertTrue(received.isEmpty(), "dismiss must not emit a result, but got $received")
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

    /**
     * Subscribes to [coord] `results` on the [TestScope.backgroundScope] using
     * an [UnconfinedTestDispatcher] tied to the same [TestScope.testScheduler]
     * as the coordinator's appScope. `CoroutineStart.UNDISPATCHED` ensures the
     * collector runs up to its first suspension (the `collect` await) before
     * this helper returns, so subsequent `execute(...)` emits are delivered.
     * Sharing the scheduler keeps emit + collect on the same virtual time.
     */
    private fun TestScope.collect(
        coord: PkmExportCoordinator,
        sink: MutableList<PkmExportResult>,
    ) = backgroundScope.launch(
        context = UnconfinedTestDispatcher(testScheduler),
        start = CoroutineStart.UNDISPATCHED,
    ) {
        coord.results.collect { result -> sink += result }
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

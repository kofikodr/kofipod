// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm

import com.kofikodr.kofipod.ai.AiSourceKind
import com.kofikodr.kofipod.ai.AiSummary
import com.kofikodr.kofipod.background.PkmExportScheduler
import com.kofikodr.kofipod.bookmarks.Bookmark
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.pkm.connections.ConnectionKind
import com.kofikodr.kofipod.pkm.connections.ExportLogEntry
import com.kofikodr.kofipod.pkm.connections.ExportLogRepository
import com.kofikodr.kofipod.pkm.sinks.ExportSink
import com.kofikodr.kofipod.pkm.sinks.ExportSinkResult
import com.kofikodr.kofipod.pkm.sinks.SinkRegistry
import com.kofikodr.kofipod.snippets.Snippet
import com.kofikodr.kofipod.snippets.SnippetFormat
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
            val clipSink = FakeSink(FakeSink.Role.Clipboard)
            val coord =
                newCoordinator(
                    deps =
                        FakeDeps(
                            snippet = sampleSnippet(),
                            episode = sampleEpisode(),
                            podcast = samplePodcast(),
                        ),
                    clipboardSink = clipSink,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.Snippet("snip-1"))
            assertEquals(PkmExportRequest.Snippet("snip-1"), coord.pendingRequest.value)

            coord.execute(PkmExportRequest.Snippet("snip-1"), PkmDestination.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(1, clipSink.clipboardCalls)
            assertEquals(0, clipSink.fileCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Copied), received.toList())
        }

    @Test
    fun bookmarkFileSucceedsAndClearsSheet() =
        runTest {
            val fileSink = FakeSink(FakeSink.Role.File)
            val coord =
                newCoordinator(
                    deps =
                        FakeDeps(
                            bookmark = sampleBookmark(),
                            episode = sampleEpisode(),
                            podcast = samplePodcast(),
                        ),
                    shareFileSink = fileSink,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.Bookmark("bm-1"))
            coord.execute(PkmExportRequest.Bookmark("bm-1"), PkmDestination.ShareFile)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, fileSink.clipboardCalls)
            assertEquals(1, fileSink.fileCalls)
            assertEquals("Share Markdown", fileSink.lastShareTitle)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Shared), received.toList())
        }

    @Test
    fun aiSummaryClipboardSucceedsAndClearsSheet() =
        runTest {
            val clipSink = FakeSink(FakeSink.Role.Clipboard)
            val coord =
                newCoordinator(
                    deps =
                        FakeDeps(
                            summary = sampleSummary(),
                            episode = sampleEpisode(),
                            podcast = samplePodcast(),
                        ),
                    clipboardSink = clipSink,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.AiSummary("e1"))
            coord.execute(PkmExportRequest.AiSummary("e1"), PkmDestination.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(1, clipSink.clipboardCalls)
            assertEquals(0, clipSink.fileCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Copied), received.toList())
        }

    @Test
    fun missingSnippetEmitsItemNotFoundAndClearsSheet() =
        runTest {
            // snippet = null -> snippetById returns null -> buildDocument short-circuits.
            val clipSink = FakeSink(FakeSink.Role.Clipboard)
            val coord =
                newCoordinator(
                    deps = FakeDeps(snippet = null, episode = sampleEpisode(), podcast = samplePodcast()),
                    clipboardSink = clipSink,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.Snippet("missing"))
            coord.execute(PkmExportRequest.Snippet("missing"), PkmDestination.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, clipSink.clipboardCalls)
            assertEquals(0, clipSink.fileCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Failed("Item not found")), received.toList())
        }

    @Test
    fun missingEpisodeEmitsItemNotFoundAndClearsSheet() =
        runTest {
            // Snippet resolves but its referenced episode does not.
            val clipSink = FakeSink(FakeSink.Role.Clipboard)
            val coord =
                newCoordinator(
                    deps = FakeDeps(snippet = sampleSnippet(), episode = null, podcast = samplePodcast()),
                    clipboardSink = clipSink,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.Snippet("snip-1"))
            coord.execute(PkmExportRequest.Snippet("snip-1"), PkmDestination.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, clipSink.clipboardCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Failed("Item not found")), received.toList())
        }

    @Test
    fun missingPodcastEmitsItemNotFoundAndClearsSheet() =
        runTest {
            // Snippet + episode resolve but the podcast row is gone.
            val clipSink = FakeSink(FakeSink.Role.Clipboard)
            val coord =
                newCoordinator(
                    deps = FakeDeps(snippet = sampleSnippet(), episode = sampleEpisode(), podcast = null),
                    clipboardSink = clipSink,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.Snippet("snip-1"))
            coord.execute(PkmExportRequest.Snippet("snip-1"), PkmDestination.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, clipSink.clipboardCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Failed("Item not found")), received.toList())
        }

    @Test
    fun aiSummaryNotFoundEmitsFailed() =
        runTest {
            // summary = null -> summaryFor returns null -> buildDocument short-circuits
            // on the AiSummary branch before any episode/podcast lookup.
            val clipSink = FakeSink(FakeSink.Role.Clipboard)
            val coord =
                newCoordinator(
                    deps = FakeDeps(summary = null, episode = sampleEpisode(), podcast = samplePodcast()),
                    clipboardSink = clipSink,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.AiSummary("e1"))
            coord.execute(PkmExportRequest.AiSummary("e1"), PkmDestination.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, clipSink.clipboardCalls)
            assertEquals(0, clipSink.fileCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Failed("Item not found")), received.toList())
        }

    @Test
    fun aiSummaryEpisodeMissingEmitsFailed() =
        runTest {
            // Summary resolves but the referenced episode row is gone.
            val clipSink = FakeSink(FakeSink.Role.Clipboard)
            val coord =
                newCoordinator(
                    deps = FakeDeps(summary = sampleSummary(), episode = null, podcast = samplePodcast()),
                    clipboardSink = clipSink,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.AiSummary("e1"))
            coord.execute(PkmExportRequest.AiSummary("e1"), PkmDestination.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, clipSink.clipboardCalls)
            assertEquals(0, clipSink.fileCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Failed("Item not found")), received.toList())
        }

    @Test
    fun aiSummaryPodcastMissingEmitsFailed() =
        runTest {
            // Summary + episode resolve but the podcast row is gone.
            val clipSink = FakeSink(FakeSink.Role.Clipboard)
            val coord =
                newCoordinator(
                    deps = FakeDeps(summary = sampleSummary(), episode = sampleEpisode(), podcast = null),
                    clipboardSink = clipSink,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.AiSummary("e1"))
            coord.execute(PkmExportRequest.AiSummary("e1"), PkmDestination.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, clipSink.clipboardCalls)
            assertEquals(0, clipSink.fileCalls)
            assertNull(coord.pendingRequest.value)
            assertEquals(listOf(PkmExportResult.Failed("Item not found")), received.toList())
        }

    @Test
    fun sinkExceptionCollapsesIntoFailedWithMessageAndClearsSheet() =
        runTest {
            val clipSink =
                FakeSink(FakeSink.Role.Clipboard).apply {
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
                    clipboardSink = clipSink,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.Snippet("snip-1"))
            coord.execute(PkmExportRequest.Snippet("snip-1"), PkmDestination.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            // The sink was reached (so the path entered the dispatch arm),
            // but the thrown exception is collapsed into a Failed result, the
            // sheet is cleared, and the message surfaces what the exception said.
            assertNull(coord.pendingRequest.value)
            assertEquals(1, received.size)
            val result = received.single()
            assertTrue(result is PkmExportResult.Failed, "expected Failed but was $result")
            assertEquals("clipboard exploded", result.message)
        }

    @Test
    fun dismissClearsPendingRequestWithoutEmittingResult() =
        runTest {
            val clipSink = FakeSink(FakeSink.Role.Clipboard)
            val coord =
                newCoordinator(
                    deps =
                        FakeDeps(
                            snippet = sampleSnippet(),
                            episode = sampleEpisode(),
                            podcast = samplePodcast(),
                        ),
                    clipboardSink = clipSink,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.show(PkmExportRequest.Snippet("snip-1"))
            coord.dismiss()
            advanceUntilIdle()
            collector.cancel()

            assertEquals(0, clipSink.clipboardCalls)
            assertEquals(0, clipSink.fileCalls)
            assertNull(coord.pendingRequest.value)
            // dismiss MUST be a pure UI clear -- no toast / snackbar / error.
            assertTrue(received.isEmpty(), "dismiss must not emit a result, but got $received")
        }

    @Test
    fun retryOfDeletedItemDrainsTheQueuedRowInsteadOfRetryingForever() =
        runTest {
            // The headline of issue #23: a connection-bound export got queued for retry,
            // then the user deleted the snippet. PkmExportWorker keeps calling retry() with
            // the row, buildDocument keeps returning null, and the old code returned without
            // marking the row terminal — so selectQueuedOrFailed() handed it back forever.
            val log = RecordingExportLog()
            log.markQueued("snippet", "snip-1", ConnectionKind.Readwise, nowMs = 1)
            assertEquals(1, log.selectQueuedOrFailed().size, "precondition: a row is waiting for the worker")

            val coord =
                newCoordinator(
                    deps = FakeDeps(snippet = null, episode = sampleEpisode(), podcast = samplePodcast()),
                    exportLog = log,
                )

            // The worker drains the queue.
            coord.retry(log.selectQueuedOrFailed().single())
            advanceUntilIdle()

            assertTrue(
                log.selectQueuedOrFailed().isEmpty(),
                "a deleted item's row must be removed so the worker stops retrying it",
            )
            assertEquals(listOf("snippet" to "snip-1"), log.deletedItems, "the dead row must be actively deleted")
        }

    @Test
    fun executeOfDeletedItemAlsoRemovesAnyOrphanRow() =
        runTest {
            // The same cleanup must happen on the direct user path, not just the worker
            // retry path — e.g. the user taps Export on a row whose underlying snippet was
            // just deleted in another pane, and a stale queued row already exists.
            val log = RecordingExportLog()
            log.markQueued("snippet", "snip-1", ConnectionKind.Readwise, nowMs = 1)
            val clipSink = FakeSink(FakeSink.Role.Clipboard)
            val coord =
                newCoordinator(
                    deps = FakeDeps(snippet = null, episode = sampleEpisode(), podcast = samplePodcast()),
                    clipboardSink = clipSink,
                    exportLog = log,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.execute(PkmExportRequest.Snippet("snip-1"), PkmDestination.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(listOf(PkmExportResult.Failed("Item not found")), received.toList())
            assertTrue(log.selectQueuedOrFailed().isEmpty(), "the orphan row is cleaned via the execute path too")
            assertEquals(listOf("snippet" to "snip-1"), log.deletedItems)
        }

    @Test
    fun retryOfDeletedBookmarkDrainsQueuedRow() =
        runTest {
            // Same path as the snippet case but exercises the ITEM_KIND_BOOKMARK constant
            // end-to-end, so a typo in that constant (or itemKindOf's bookmark arm) is caught.
            val log = RecordingExportLog()
            log.markQueued("bookmark", "bm-1", ConnectionKind.Readwise, nowMs = 1)
            val coord =
                newCoordinator(
                    deps = FakeDeps(bookmark = null, episode = sampleEpisode(), podcast = samplePodcast()),
                    exportLog = log,
                )

            coord.retry(log.selectQueuedOrFailed().single())
            advanceUntilIdle()

            assertTrue(log.selectQueuedOrFailed().isEmpty(), "a deleted bookmark's row must be removed")
            assertEquals(listOf("bookmark" to "bm-1"), log.deletedItems)
        }

    @Test
    fun successfulExportDoesNotDeleteTheLogRow() =
        runTest {
            // Guard against an over-eager cleanup: deleteByItem must fire ONLY on the
            // item-not-found path, never on a live, successfully-exported item.
            val log = RecordingExportLog()
            val clipSink = FakeSink(FakeSink.Role.Clipboard)
            val coord =
                newCoordinator(
                    deps = FakeDeps(snippet = sampleSnippet(), episode = sampleEpisode(), podcast = samplePodcast()),
                    clipboardSink = clipSink,
                    exportLog = log,
                )
            val received = mutableListOf<PkmExportResult>()
            val collector = collect(coord, received)

            coord.execute(PkmExportRequest.Snippet("snip-1"), PkmDestination.Clipboard)
            advanceUntilIdle()
            collector.cancel()

            assertEquals(listOf(PkmExportResult.Copied), received.toList(), "precondition: the export succeeded")
            assertTrue(log.deletedItems.isEmpty(), "a successful export must never delete the item's log row")
        }

    @Test
    fun retryOfRowWithUnknownItemKindDeletesTheDeadRow() =
        runTest {
            // Defense in depth for the same "dead row retried forever" class: a row whose
            // itemKind this build no longer understands can never be turned into a request,
            // so retry() must delete it rather than early-return and leave it queued.
            val log = RecordingExportLog()
            log.markQueued("mystery", "x-1", ConnectionKind.Readwise, nowMs = 1)
            val coord = newCoordinator(deps = FakeDeps(), exportLog = log)

            coord.retry(log.selectQueuedOrFailed().single())
            advanceUntilIdle()

            assertTrue(log.selectQueuedOrFailed().isEmpty(), "an unprocessable row must not survive a retry pass")
            assertEquals(listOf("mystery" to "x-1"), log.deletedItems)
        }

    @Test
    fun retryOfRowWithUnroutableDestinationDeletesTheDeadRow() =
        runTest {
            // The other half of retry()'s guard: ConnectionKind.Notion exists in the enum
            // but has no PkmDestination entry, so destinationFromKind returns null. Such a
            // row (e.g. written by a newer build, then downgraded) can never be routed, so
            // it must be deleted rather than left to retry forever.
            val log = RecordingExportLog()
            log.markQueued("snippet", "snip-1", ConnectionKind.Notion, nowMs = 1)
            val coord = newCoordinator(deps = FakeDeps(), exportLog = log)

            coord.retry(log.selectQueuedOrFailed().single())
            advanceUntilIdle()

            assertTrue(log.selectQueuedOrFailed().isEmpty(), "a row with an unroutable destination must not survive a retry pass")
            assertEquals(listOf("snippet" to "snip-1"), log.deletedItems)
        }

    // ─── Test plumbing ────────────────────────────────────────────────────────

    /**
     * Builds a coordinator wired to a [TestScope]-bound [UnconfinedTestDispatcher].
     * Using `Unconfined` makes the `appScope.launch { ... }` body run inline on
     * the test thread, so post-`execute()` assertions don't need an explicit
     * `advanceUntilIdle`. Stays inside `TestScope` so failures don't leak as
     * uncaught.
     */
    private fun TestScope.newCoordinator(
        deps: PkmExportDeps,
        formatter: MarkdownFormatter = MarkdownFormatterImpl(),
        clipboardSink: ExportSink = FakeSink(FakeSink.Role.Clipboard),
        shareFileSink: ExportSink = FakeSink(FakeSink.Role.File),
        exportLog: ExportLogRepository = NoOpExportLog(),
    ): PkmExportCoordinator {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return PkmExportCoordinator(
            deps = deps,
            formatter = formatter,
            sinks = SinkRegistry(emptyMap()),
            exportLog = exportLog,
            scheduler = NoOpScheduler(),
            appScope = CoroutineScope(dispatcher),
            clipboardSink = clipboardSink,
            shareFileSink = shareFileSink,
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

    // ─── Test doubles ─────────────────────────────────────────────────────────

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

    /**
     * ExportSink fake that intercepts calls and records clipboard / file dispatch.
     * Each instance is wired to exactly one coordinator position (clipboardSink
     * OR shareFileSink), so the [role] distinguishes which counter to increment.
     * This preserves the Slice 5 test assertions which checked `clipboardCalls`
     * and `fileCalls` on a single [MarkdownSink] object — now the caller passes
     * two separate fakes and queries the one it cares about.
     *
     * [lastShareTitle] mirrors the old `MarkdownSink.exportAsFile(shareTitle)` so
     * the file-path assertion in [bookmarkFileSucceedsAndClearsSheet] keeps working:
     * the coordinator hardcodes the title as "Share Markdown" via [ShareFileSink],
     * and that value propagates through the document title field.
     */
    private class FakeSink(private val role: Role = Role.Clipboard) : ExportSink {
        enum class Role { Clipboard, File }

        var clipboardCalls = 0
        var fileCalls = 0
        var lastShareTitle: String? = null
        var throwOnClipboard: Throwable? = null
        var throwOnFile: Throwable? = null

        override suspend fun export(
            document: MarkdownDocument,
            request: PkmExportRequest,
            priorExternalId: String?,
        ): ExportSinkResult {
            when (role) {
                Role.Clipboard -> {
                    throwOnClipboard?.let { throw it }
                    clipboardCalls += 1
                }
                Role.File -> {
                    throwOnFile?.let { throw it }
                    fileCalls += 1
                    lastShareTitle = "Share Markdown"
                }
            }
            return ExportSinkResult.Success(externalId = null)
        }
    }

    /**
     * No-op ExportLog for tests that don't need log verification.
     * Prevents the coordinator constructor from requiring a real DB.
     */
    private class NoOpExportLog : ExportLogRepository {
        override suspend fun find(
            itemKind: String,
            itemId: String,
            destinationKind: ConnectionKind,
        ): ExportLogEntry? = null

        override suspend fun selectQueuedOrFailed(): List<ExportLogEntry> = emptyList()

        override suspend fun recordSuccess(
            itemKind: String,
            itemId: String,
            destinationKind: ConnectionKind,
            externalId: String?,
            nowMs: Long,
        ) = Unit

        override suspend fun markQueued(
            itemKind: String,
            itemId: String,
            destinationKind: ConnectionKind,
            nowMs: Long,
        ) = Unit

        override suspend fun markFailed(
            itemKind: String,
            itemId: String,
            destinationKind: ConnectionKind,
            message: String,
            nowMs: Long,
        ) = Unit

        override suspend fun deleteByItem(
            itemKind: String,
            itemId: String,
        ) = Unit
    }

    private class NoOpScheduler : PkmExportScheduler {
        override fun enqueue() = Unit
    }

    /**
     * In-memory [ExportLogRepository] that behaves like the real ledger: one row per
     * `(itemKind, itemId, destinationKind)`, with [selectQueuedOrFailed] returning rows
     * in `queued`/`failed` status — exactly what `PkmExportWorker` drains. [deletedItems]
     * records every [deleteByItem] call so a test can assert a dead row was actively
     * removed (issue #23), not merely absent.
     */
    private class RecordingExportLog : ExportLogRepository {
        private val rows = mutableMapOf<Triple<String, String, ConnectionKind>, ExportLogEntry>()
        val deletedItems = mutableListOf<Pair<String, String>>()

        override suspend fun find(
            itemKind: String,
            itemId: String,
            destinationKind: ConnectionKind,
        ): ExportLogEntry? = rows[Triple(itemKind, itemId, destinationKind)]

        override suspend fun selectQueuedOrFailed(): List<ExportLogEntry> =
            rows.values.filter { it.status == "queued" || it.status == "failed" }

        override suspend fun recordSuccess(
            itemKind: String,
            itemId: String,
            destinationKind: ConnectionKind,
            externalId: String?,
            nowMs: Long,
        ) {
            rows[Triple(itemKind, itemId, destinationKind)] =
                ExportLogEntry(itemKind, itemId, destinationKind, externalId, nowMs, "success", null)
        }

        override suspend fun markQueued(
            itemKind: String,
            itemId: String,
            destinationKind: ConnectionKind,
            nowMs: Long,
        ) {
            rows[Triple(itemKind, itemId, destinationKind)] =
                ExportLogEntry(itemKind, itemId, destinationKind, null, nowMs, "queued", null)
        }

        override suspend fun markFailed(
            itemKind: String,
            itemId: String,
            destinationKind: ConnectionKind,
            message: String,
            nowMs: Long,
        ) {
            rows[Triple(itemKind, itemId, destinationKind)] =
                ExportLogEntry(itemKind, itemId, destinationKind, null, nowMs, "failed", message)
        }

        override suspend fun deleteByItem(
            itemKind: String,
            itemId: String,
        ) {
            deletedItems += itemKind to itemId
            rows.keys.removeAll { it.first == itemKind && it.second == itemId }
        }
    }

    // ─── Sample fixtures ──────────────────────────────────────────────────────
    // Field shapes mirror MarkdownFormatterTest's so the formatter does not
    // throw on any of these values.

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

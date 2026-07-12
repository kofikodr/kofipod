// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm

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

/**
 * Slice 6 coordinator behaviour: dispatch by [PkmDestination], write to
 * [ExportLogRepository], schedule retries on transient failure, and surface
 * prior external IDs to connection-bound sinks for idempotency.
 *
 * Fakes are file-local per the plan note ("for now keep file-local").
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PkmExportCoordinatorSlice6Test {
    // ─── 1. success → log written, result emitted ─────────────────────────────

    @Test
    fun successWritesExportLogAndEmitsResult() =
        runTest {
            val log = FakeExportLog()
            val sinks =
                SinkRegistry(
                    mapOf(ConnectionKind.Readwise to RecordingSink(ExportSinkResult.Success(externalId = "ext-1"))),
                )
            val scheduler = FakeScheduler()
            val coord = newCoordinator(log = log, sinks = sinks, scheduler = scheduler)

            val received = mutableListOf<PkmExportResult>()
            val collector = collectResults(coord, received)

            coord.execute(PkmExportRequest.Bookmark("b1"), PkmDestination.Readwise)
            advanceUntilIdle()
            collector.cancel()

            val entry = log.entries.firstOrNull { it.itemId == "b1" && it.destinationKind == ConnectionKind.Readwise }
            checkNotNull(entry) { "ExportLog entry not written for b1/Readwise" }
            assertEquals("success", entry.status)
            assertEquals("ext-1", entry.externalId)
            // pin down connection-bound success emits Exported, not Shared
            assertEquals<List<PkmExportResult>>(listOf(PkmExportResult.Exported), received)
        }

    // ─── 2. re-export passes prior externalId to sink ─────────────────────────

    @Test
    fun reExportPassesPriorExternalIdToSink() =
        runTest {
            val log = FakeExportLog()
            log.recordSuccess("bookmark", "b1", ConnectionKind.Readwise, "prior-ext-9", 0L)

            val sink = RecordingSink(ExportSinkResult.Success(externalId = "prior-ext-9"))
            val sinks = SinkRegistry(mapOf(ConnectionKind.Readwise to sink))
            val coord = newCoordinator(log = log, sinks = sinks)

            val received = mutableListOf<PkmExportResult>()
            val collector = collectResults(coord, received)

            coord.execute(PkmExportRequest.Bookmark("b1"), PkmDestination.Readwise)
            advanceUntilIdle()
            collector.cancel()

            assertEquals("prior-ext-9", sink.lastPriorExternalId)
            // connection-bound re-export also emits Exported
            assertEquals<List<PkmExportResult>>(listOf(PkmExportResult.Exported), received)
        }

    // ─── 3. transient failure → queued log + scheduler enqueued ──────────────

    @Test
    fun transientFailureMarksQueuedAndSchedulesWorker() =
        runTest {
            val log = FakeExportLog()
            val sinks =
                SinkRegistry(
                    mapOf(ConnectionKind.Readwise to RecordingSink(ExportSinkResult.TransientFailure("network"))),
                )
            val scheduler = FakeScheduler()
            val coord = newCoordinator(log = log, sinks = sinks, scheduler = scheduler)

            val received = mutableListOf<PkmExportResult>()
            val collector = collectResults(coord, received)

            coord.execute(PkmExportRequest.Bookmark("b1"), PkmDestination.Readwise)
            advanceUntilIdle()
            collector.cancel()

            val entry = log.entries.firstOrNull()
            checkNotNull(entry) { "ExportLog entry not written" }
            assertEquals("queued", entry.status)
            assertEquals(1, scheduler.enqueued)
            // transient failure surfaces as Failed with the sink's message
            assertEquals<List<PkmExportResult>>(listOf(PkmExportResult.Failed("network")), received)
        }

    // ─── 4. permanent failure → failed log, no scheduler ─────────────────────

    @Test
    fun permanentFailureMarksFailedNoWorker() =
        runTest {
            val log = FakeExportLog()
            val sinks =
                SinkRegistry(
                    mapOf(ConnectionKind.Readwise to RecordingSink(ExportSinkResult.PermanentFailure("not connected"))),
                )
            val scheduler = FakeScheduler()
            val coord = newCoordinator(log = log, sinks = sinks, scheduler = scheduler)

            val received = mutableListOf<PkmExportResult>()
            val collector = collectResults(coord, received)

            coord.execute(PkmExportRequest.Bookmark("b1"), PkmDestination.Readwise)
            advanceUntilIdle()
            collector.cancel()

            val entry = log.entries.firstOrNull()
            checkNotNull(entry) { "ExportLog entry not written" }
            assertEquals("failed", entry.status)
            assertEquals(0, scheduler.enqueued)
            // permanent failure surfaces as Failed with the sink's message, no retry
            assertEquals<List<PkmExportResult>>(listOf(PkmExportResult.Failed("not connected")), received)
        }

    // ─── helpers ──────────────────────────────────────────────────────────────

    private fun TestScope.newCoordinator(
        log: FakeExportLog = FakeExportLog(),
        sinks: SinkRegistry = SinkRegistry(emptyMap()),
        scheduler: FakeScheduler = FakeScheduler(),
        deps: PkmExportDeps = FakeDeps6(bookmark = sampleBookmark(), episode = sampleEpisode(), podcast = samplePodcast()),
        formatter: MarkdownFormatter = MarkdownFormatterImpl(),
        clipboardSink: ExportSink = RecordingSink(ExportSinkResult.Success(null)),
        shareFileSink: ExportSink = RecordingSink(ExportSinkResult.Success(null)),
    ): PkmExportCoordinator {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return PkmExportCoordinator(
            deps = deps,
            formatter = formatter,
            sinks = sinks,
            exportLog = log,
            scheduler = scheduler,
            appScope = CoroutineScope(dispatcher),
            clipboardSink = clipboardSink,
            shareFileSink = shareFileSink,
        )
    }

    private fun TestScope.collectResults(
        coord: PkmExportCoordinator,
        sink: MutableList<PkmExportResult>,
    ) = backgroundScope.launch(
        context = UnconfinedTestDispatcher(testScheduler),
        start = CoroutineStart.UNDISPATCHED,
    ) {
        coord.results.collect { result -> sink += result }
    }

    // ─── fakes ────────────────────────────────────────────────────────────────

    private class FakeDeps6(
        val snippet: Snippet? = null,
        val bookmark: Bookmark? = null,
        val summary: com.kofikodr.kofipod.ai.AiSummary? = null,
        val episode: Episode? = null,
        val podcast: Podcast? = null,
    ) : PkmExportDeps {
        override suspend fun snippetById(id: String): Snippet? = snippet

        override suspend fun bookmarkById(id: String): Bookmark? = bookmark

        override suspend fun summaryFor(episodeId: String): AiSummary? = summary

        override fun episode(id: String): Episode? = episode

        override fun podcast(id: String): Podcast? = podcast
    }

    /** Records all calls to [export] so tests can verify payload passing. */
    private class RecordingSink(private val result: ExportSinkResult) : ExportSink {
        var lastPriorExternalId: String? = "unset" // distinct sentinel so null-passed is observable
        var callCount = 0

        override suspend fun export(
            document: MarkdownDocument,
            request: PkmExportRequest,
            priorExternalId: String?,
        ): ExportSinkResult {
            callCount += 1
            lastPriorExternalId = priorExternalId
            return result
        }
    }

    private class FakeExportLog : ExportLogRepository {
        // Use a fake DB-less implementation for testing
        val entries = mutableListOf<ExportLogEntry>()

        override suspend fun find(
            itemKind: String,
            itemId: String,
            destinationKind: ConnectionKind,
        ): ExportLogEntry? =
            entries.firstOrNull {
                it.itemKind == itemKind && it.itemId == itemId && it.destinationKind == destinationKind
            }

        override suspend fun selectQueuedOrFailed(): List<ExportLogEntry> =
            entries.filter { it.status == "queued" || it.status == "failed" }

        override suspend fun recordSuccess(
            itemKind: String,
            itemId: String,
            destinationKind: ConnectionKind,
            externalId: String?,
            nowMs: Long,
        ) {
            upsertEntry(itemKind, itemId, destinationKind, externalId, "success", null, nowMs)
        }

        override suspend fun markQueued(
            itemKind: String,
            itemId: String,
            destinationKind: ConnectionKind,
            externalId: String?,
            nowMs: Long,
        ) {
            upsertEntry(itemKind, itemId, destinationKind, externalId, "queued", null, nowMs)
        }

        override suspend fun markFailed(
            itemKind: String,
            itemId: String,
            destinationKind: ConnectionKind,
            externalId: String?,
            message: String,
            nowMs: Long,
        ) {
            upsertEntry(itemKind, itemId, destinationKind, externalId, "failed", message, nowMs)
        }

        override suspend fun deleteByItem(
            itemKind: String,
            itemId: String,
        ) {
            entries.removeAll { it.itemKind == itemKind && it.itemId == itemId }
        }

        private fun upsertEntry(
            itemKind: String,
            itemId: String,
            destinationKind: ConnectionKind,
            externalId: String?,
            status: String,
            errorMessage: String?,
            nowMs: Long,
        ) {
            val idx =
                entries.indexOfFirst {
                    it.itemKind == itemKind && it.itemId == itemId && it.destinationKind == destinationKind
                }
            val entry =
                ExportLogEntry(
                    itemKind = itemKind,
                    itemId = itemId,
                    destinationKind = destinationKind,
                    externalId = externalId,
                    exportedAtMs = nowMs,
                    status = status,
                    errorMessage = errorMessage,
                )
            if (idx >= 0) entries[idx] = entry else entries += entry
        }
    }

    private class FakeScheduler : PkmExportScheduler {
        var enqueued = 0

        override fun enqueue() {
            enqueued += 1
        }
    }

    // ─── sample fixtures ──────────────────────────────────────────────────────

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
            lastSeenAt = null,
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

    private fun sampleBookmark() =
        Bookmark(
            id = "b1",
            episodeId = "e1",
            podcastId = "p1",
            timestampMs = 0,
            note = null,
            createdAtMs = 0,
        )
}

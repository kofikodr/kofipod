// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.repo

import com.kofikodr.kofipod.data.repo.DownloadRepository
import com.kofikodr.kofipod.data.repo.DownloadRepository.Companion.STATE_WAITING_WIFI
import com.kofikodr.kofipod.data.repo.SettingsRepository
import com.kofikodr.kofipod.db.KofipodDatabase
import com.kofikodr.kofipod.downloads.DownloadEngineApi
import com.kofikodr.kofipod.downloads.DownloadJob
import com.kofikodr.kofipod.downloads.DownloadProgress
import com.kofikodr.kofipod.network.NetworkMonitor
import com.kofikodr.kofipod.network.NetworkType
import com.kofikodr.kofipod.snippets.FileCheckerApi
import com.kofikodr.kofipod.testing.inMemoryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadRepositoryTest {
    @Test
    fun localUriFor_returnsFileUri_forCompletedDownloads_andNullOtherwise() =
        runHarnessTest {
            db.downloadQueries.upsert(
                episodeId = "ep-done",
                state = "Completed",
                localPath = "/path/ep-done.mp3",
                downloadedBytes = 1_000L,
                totalBytes = 1_000L,
                source = "Manual",
                startedAt = null,
                completedAt = 1L,
                errorMessage = null,
            )
            db.downloadQueries.upsert(
                episodeId = "ep-pending",
                state = "Queued",
                localPath = null,
                downloadedBytes = 0L,
                totalBytes = 0L,
                source = "Manual",
                startedAt = null,
                completedAt = null,
                errorMessage = null,
            )

            assertEquals("file:///path/ep-done.mp3", repo.localUriFor("ep-done"))
            assertNull(repo.localUriFor("ep-pending"))
            assertNull(repo.localUriFor("ep-missing"))
        }

    @Test
    fun localPathFor_selfHeals_whenLocalFileMissing() =
        runHarnessTest(fileChecker = FakeFileChecker(existing = emptySet())) {
            db.downloadQueries.upsert(
                episodeId = "ep-orphan",
                state = "Completed",
                localPath = "/path/ep-orphan.mp3",
                downloadedBytes = 1_000L,
                totalBytes = 1_000L,
                source = "Manual",
                startedAt = null,
                completedAt = 1L,
                errorMessage = null,
            )

            // File is missing → self-heal returns null AND wipes the row.
            assertNull(repo.localPathFor("ep-orphan"))
            assertNull(
                db.downloadQueries.selectByEpisode("ep-orphan").executeAsOneOrNull(),
                "stale Download row should be deleted on missing-file detection",
            )
            assertEquals(
                listOf("ep-orphan"),
                engine.deleted,
                "engine.delete must run alongside the row wipe to clean any partial state",
            )
        }

    @Test
    fun resolvedSourceUrl_fallsBackToStream_whenDownloadedFileMissing() =
        runHarnessTest(fileChecker = FakeFileChecker(existing = emptySet())) {
            seedEpisode("ep-stream-fallback", mime = "audio/mpeg")
            db.downloadQueries.upsert(
                episodeId = "ep-stream-fallback",
                state = "Completed",
                localPath = "/gone/ep-stream-fallback.mp3",
                downloadedBytes = 1L,
                totalBytes = 1L,
                source = "Manual",
                startedAt = null,
                completedAt = 1L,
                errorMessage = null,
            )

            val resolved = repo.resolvedSourceUrl("ep-stream-fallback", "https://example.com/stream.mp3")

            assertEquals(
                "https://example.com/stream.mp3",
                resolved,
                "resolver must fall through to the streaming URL when the local file is gone",
            )
            assertNull(
                db.downloadQueries.selectByEpisode("ep-stream-fallback").executeAsOneOrNull(),
                "self-heal must wipe the stale row even when reached through resolvedSourceUrl",
            )
            assertEquals(
                listOf("ep-stream-fallback"),
                engine.deleted,
                "engine.delete must fire as part of the same self-heal",
            )
        }

    @Test
    fun localPathFor_doesNotTouch_nonCompletedRowWithLocalPath() =
        runHarnessTest(fileChecker = FakeFileChecker(existing = emptySet())) {
            // A Queued row with a stray localPath (e.g. crashed mid-download) must not be
            // collateral damage of the self-heal — only Completed rows are claimed by the
            // resolver in the first place. The SQL filter (`WHERE state = 'Completed'`)
            // already excludes this row; the test pins that contract so a future query
            // edit can't silently widen the blast radius.
            db.downloadQueries.upsert(
                episodeId = "ep-queued",
                state = "Queued",
                localPath = "/in-flight/ep-queued.mp3.part",
                downloadedBytes = 1L,
                totalBytes = 100L,
                source = "Manual",
                startedAt = 1L,
                completedAt = null,
                errorMessage = null,
            )

            assertNull(repo.localPathFor("ep-queued"))
            assertEquals(
                "Queued",
                stateOf("ep-queued"),
                "non-Completed rows must survive a localPathFor lookup intact",
            )
            assertTrue(engine.deleted.isEmpty(), "self-heal must not delete in-flight rows")
        }

    @Test
    fun localPathFor_returnsPath_whenFileExistsOnDisk() =
        runHarnessTest(fileChecker = FakeFileChecker(existing = setOf("/real/ep-here.mp3"))) {
            db.downloadQueries.upsert(
                episodeId = "ep-here",
                state = "Completed",
                localPath = "/real/ep-here.mp3",
                downloadedBytes = 1_000L,
                totalBytes = 1_000L,
                source = "Manual",
                startedAt = null,
                completedAt = 1L,
                errorMessage = null,
            )

            assertEquals("/real/ep-here.mp3", repo.localPathFor("ep-here"))
            assertEquals("file:///real/ep-here.mp3", repo.localUriFor("ep-here"))
            assertTrue(engine.deleted.isEmpty(), "extant file must not trigger any cleanup")
        }

    @Test
    fun enqueue_queuesImmediately_whenOnWifi() =
        runHarnessTest(network = NetworkType.Wifi, wifiOnly = true) {
            seedEpisode("ep-1", mime = "audio/mpeg")

            repo.enqueue("ep-1", "https://example.com/ep-1.mp3", "ep-1.mp3", DownloadJob.Source.Manual)

            assertEquals(
                listOf("ep-1"),
                engine.enqueued.map { it.episodeId },
                "engine must receive the job when Wi-Fi gate is satisfied",
            )
            assertEquals("Queued", stateOf("ep-1"))
        }

    @Test
    fun enqueue_queuesImmediately_whenMeteredAndWifiOnlyOff() =
        runHarnessTest(network = NetworkType.Metered, wifiOnly = false) {
            seedEpisode("ep-2", mime = "audio/mpeg")

            repo.enqueue("ep-2", "https://example.com/ep-2.mp3", "ep-2.mp3", DownloadJob.Source.Auto)

            assertEquals(1, engine.enqueued.size, "metered + wifiOnly=false should download immediately")
            assertEquals("Queued", stateOf("ep-2"))
        }

    @Test
    fun enqueue_defers_whenWifiOnlyAndMetered() =
        runHarnessTest(network = NetworkType.Metered, wifiOnly = true) {
            repo.enqueue("ep-3", "https://example.com/ep-3.mp3", "ep-3.mp3", DownloadJob.Source.Auto)

            assertTrue(engine.enqueued.isEmpty(), "engine must NOT be called while the Wi-Fi gate is closed")
            assertEquals(STATE_WAITING_WIFI, stateOf("ep-3"))
        }

    @Test
    fun enqueue_defers_whenNoNetwork() =
        runHarnessTest(network = NetworkType.None, wifiOnly = false) {
            repo.enqueue("ep-none", "https://example.com/ep-none.mp3", "ep-none.mp3", DownloadJob.Source.Manual)

            assertTrue(engine.enqueued.isEmpty(), "no network must defer even with wifiOnly=false")
            assertEquals(STATE_WAITING_WIFI, stateOf("ep-none"))
        }

    @Test
    fun deferredRow_flushesToEngine_whenNetworkTransitionsToWifi() =
        runHarnessTest(network = NetworkType.Metered, wifiOnly = true) {
            seedEpisode("ep-4", mime = "audio/mpeg")
            repo.enqueue("ep-4", "https://example.com/ep-4.mp3", "ep-4.mp3", DownloadJob.Source.Auto)
            assertEquals(STATE_WAITING_WIFI, stateOf("ep-4"))

            network.value = NetworkType.Wifi

            // Ordering: DB must flip to Queued before the engine sees the job, otherwise a
            // crash between the two could leave an in-flight download with stale state.
            assertEquals("Queued", stateOf("ep-4"))
            assertEquals(
                listOf("ep-4"),
                engine.enqueued.map { it.episodeId },
                "flush should send the deferred job to the engine once Wi-Fi is available",
            )
        }

    @Test
    fun deferredRow_flushes_whenWifiOnlyToggledOff() =
        runHarnessTest(network = NetworkType.Metered, wifiOnly = true) {
            seedEpisode("ep-5", mime = "audio/mp4")
            repo.enqueue("ep-5", "https://example.com/ep-5.m4a", "ep-5.m4a", DownloadJob.Source.Manual)
            assertEquals(STATE_WAITING_WIFI, stateOf("ep-5"))

            settings.setWifiOnly(false)

            assertEquals("Queued", stateOf("ep-5"))
            assertEquals(1, engine.enqueued.size, "flipping wifiOnly off on metered must flush deferred rows")
            assertEquals("ep-5", engine.enqueued.single().episodeId)
        }

    @Test
    fun deferredRow_doesNotFlush_whenStillOnMeteredWithWifiOnlyOn() =
        runHarnessTest(network = NetworkType.Metered, wifiOnly = true) {
            seedEpisode("ep-6", mime = "audio/mpeg")
            repo.enqueue("ep-6", "https://example.com/ep-6.mp3", "ep-6.mp3", DownloadJob.Source.Auto)

            // No-op "type" update (same value). distinctUntilChanged must suppress this.
            network.value = NetworkType.Metered

            assertTrue(engine.enqueued.isEmpty(), "repeated metered signal must not unlock downloads")
            assertEquals(STATE_WAITING_WIFI, stateOf("ep-6"))
        }

    @Test
    fun queuedInFlightRow_staysQueued_onWifiToMeteredTransition() =
        runHarnessTest(network = NetworkType.Wifi, wifiOnly = true) {
            seedEpisode("ep-inflight", mime = "audio/mpeg")
            repo.enqueue(
                "ep-inflight",
                "https://example.com/ep-inflight.mp3",
                "ep-inflight.mp3",
                DownloadJob.Source.Manual,
            )
            assertEquals("Queued", stateOf("ep-inflight"))
            val enqueuedBefore = engine.enqueued.size

            // Documents the deliberate decision: once a job is with the engine, a subsequent
            // metered/cellular transition does NOT cancel or re-defer it.
            network.value = NetworkType.Metered

            assertEquals("Queued", stateOf("ep-inflight"), "in-flight downloads must not be re-deferred")
            assertEquals(enqueuedBefore, engine.enqueued.size, "no re-enqueue on gate close")
            assertTrue(engine.cancelled.isEmpty(), "gate close must not cancel in-flight downloads")
        }

    @Test
    fun evictUntilUnderCap_noArg_readsCapFromSettings_andEvictsAutoCompletedOldestFirst() =
        runHarnessTest(network = NetworkType.Wifi, wifiOnly = false) {
            listOf("old" to 1_000L, "mid" to 2_000L, "new" to 3_000L).forEach { (id, completedAt) ->
                insertCompleted(id, source = "Auto", totalBytes = TEN_MB, completedAt = completedAt)
            }
            settings.setStorageCapBytes(15L * 1024 * 1024)

            repo.evictUntilUnderCap()

            val remaining = db.downloadQueries.selectAll().executeAsList().map { it.episodeId }.toSet()
            assertEquals(setOf("new"), remaining, "only the newest Auto download should survive the 15 MB cap")
            assertEquals(
                setOf("old", "mid"),
                engine.deleted.toSet(),
                "evicted rows must have their engine.delete() called for file cleanup",
            )
        }

    @Test
    fun evictUntilUnderCap_preservesManualDownloads_evenWhenOverCap() =
        runHarnessTest(network = NetworkType.Wifi, wifiOnly = false) {
            insertCompleted("auto-old", source = "Auto", totalBytes = TEN_MB, completedAt = 1_000L)
            insertCompleted("manual-older", source = "Manual", totalBytes = TEN_MB, completedAt = 500L)
            settings.setStorageCapBytes(5L * 1024 * 1024)

            repo.evictUntilUnderCap()

            val remaining = db.downloadQueries.selectAll().executeAsList().map { it.episodeId }.toSet()
            assertTrue(
                "manual-older" in remaining,
                "Manual downloads must never be evicted, even if they're the oldest rows",
            )
            assertTrue(
                "auto-old" !in remaining,
                "Auto downloads should still be evicted when the cap is exceeded",
            )
        }

    @Test
    fun evictUntilUnderCap_isNoOp_whenAlreadyUnderCap() =
        runHarnessTest(network = NetworkType.Wifi, wifiOnly = false) {
            insertCompleted("small-auto", source = "Auto", totalBytes = 5L * 1024 * 1024, completedAt = 1_000L)
            settings.setStorageCapBytes(10L * 1024 * 1024)

            repo.evictUntilUnderCap()

            val remaining = db.downloadQueries.selectAll().executeAsList().map { it.episodeId }.toSet()
            assertEquals(setOf("small-auto"), remaining, "nothing should be evicted below the cap")
            assertTrue(engine.deleted.isEmpty(), "engine.delete must not be called when under cap")
        }

    @Test
    fun evictUntilUnderCap_isNoOp_atExactCapBoundary() =
        runHarnessTest(network = NetworkType.Wifi, wifiOnly = false) {
            insertCompleted("exact-auto", source = "Auto", totalBytes = TEN_MB, completedAt = 1_000L)
            settings.setStorageCapBytes(TEN_MB)

            repo.evictUntilUnderCap()

            val remaining = db.downloadQueries.selectAll().executeAsList().map { it.episodeId }.toSet()
            assertEquals(
                setOf("exact-auto"),
                remaining,
                "rows that bring the total to exactly the cap must not be evicted (<= semantics)",
            )
            assertTrue(engine.deleted.isEmpty())
        }

    // ---------- harness ----------

    private class Harness(
        val db: KofipodDatabase,
        val repo: DownloadRepository,
        val engine: RecordingDownloadEngine,
        val settings: SettingsRepository,
        val scope: CoroutineScope,
        val network: MutableStateFlow<NetworkType>,
    ) {
        fun stateOf(episodeId: String): String? = db.downloadQueries.selectByEpisode(episodeId).executeAsOneOrNull()?.state

        fun seedEpisode(
            id: String,
            mime: String,
        ) {
            db.podcastQueries.insert(
                id = "p-$id",
                title = "Podcast $id",
                author = "",
                description = "",
                artworkUrl = "",
                feedUrl = "",
                listId = null,
                autoDownloadEnabled = 0L,
                notifyNewEpisodesEnabled = 1L,
                lastCheckedAt = 0L,
                addedAt = 0L,
                primaryCategory = "",
            )
            db.episodeQueries.insert(
                id = id,
                podcastId = "p-$id",
                guid = id,
                title = "Ep $id",
                description = "",
                publishedAt = 0L,
                durationSec = 0L,
                enclosureUrl = "https://example.com/$id",
                enclosureMimeType = mime,
                fileSizeBytes = 0L,
                seasonNumber = null,
                episodeNumber = null,
                imageUrl = "",
                chaptersUrl = null,
                transcriptUrl = null,
            )
        }

        fun insertCompleted(
            episodeId: String,
            source: String,
            totalBytes: Long,
            completedAt: Long,
        ) {
            db.downloadQueries.upsert(
                episodeId = episodeId,
                state = "Completed",
                localPath = "/tmp/$episodeId.mp3",
                downloadedBytes = totalBytes,
                totalBytes = totalBytes,
                source = source,
                startedAt = null,
                completedAt = completedAt,
                errorMessage = null,
            )
        }
    }

    /**
     * Boot a repository wired to an [UnconfinedTestDispatcher] for both the app scope and
     * the [SettingsRepository] flow context, then run a test block. With both sides on the
     * same test dispatcher, setting writes propagate through `combine()` synchronously —
     * no wall-clock waits required.
     */
    private fun runHarnessTest(
        network: NetworkType = NetworkType.Wifi,
        wifiOnly: Boolean = false,
        fileChecker: FileCheckerApi = AlwaysExistsFileChecker,
        block: suspend Harness.() -> Unit,
    ) = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val db = inMemoryDatabase()
        val engine = RecordingDownloadEngine()
        val settings = SettingsRepository(db, flowContext = dispatcher)
        settings.setWifiOnly(wifiOnly)
        val networkFlow = MutableStateFlow(network)
        val monitor =
            object : NetworkMonitor {
                override val type: StateFlow<NetworkType> = networkFlow.asStateFlow()
            }
        val testScope = TestScope(dispatcher)
        val repo =
            DownloadRepository(
                db,
                engine,
                settings,
                monitor,
                testScope,
                com.kofikodr.kofipod.diagnostics.NoOpTelemetry,
                fileChecker,
            )

        val harness = Harness(db, repo, engine, settings, testScope, networkFlow)
        try {
            harness.block()
        } finally {
            testScope.cancel()
        }
    }

    private class FakeFileChecker(private val existing: Set<String>) : FileCheckerApi {
        override fun exists(path: String): Boolean = path in existing
    }

    private object AlwaysExistsFileChecker : FileCheckerApi {
        override fun exists(path: String): Boolean = true
    }

    private class RecordingDownloadEngine : DownloadEngineApi {
        val enqueued = mutableListOf<DownloadJob>()
        val cancelled = mutableListOf<String>()
        val deleted = mutableListOf<String>()

        override val events: SharedFlow<DownloadProgress> = MutableSharedFlow<DownloadProgress>().asSharedFlow()

        override fun enqueue(job: DownloadJob) {
            enqueued += job
        }

        override fun cancel(episodeId: String) {
            cancelled += episodeId
        }

        override fun delete(episodeId: String) {
            deleted += episodeId
        }
    }

    companion object {
        private const val TEN_MB: Long = 10L * 1024 * 1024
    }
}

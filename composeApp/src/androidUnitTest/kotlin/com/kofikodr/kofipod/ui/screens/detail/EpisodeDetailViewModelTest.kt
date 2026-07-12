// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail

import com.kofikodr.kofipod.ai.AiConfigRepository
import com.kofikodr.kofipod.ai.AiSummary
import com.kofikodr.kofipod.ai.KeyVault
import com.kofikodr.kofipod.background.PkmExportScheduler
import com.kofikodr.kofipod.bookmarks.Bookmark
import com.kofikodr.kofipod.bookmarks.BookmarkRepository
import com.kofikodr.kofipod.data.repo.ChaptersRepository
import com.kofikodr.kofipod.data.repo.DownloadRepository
import com.kofikodr.kofipod.data.repo.EpisodeSource
import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.data.repo.PlaybackRepository
import com.kofikodr.kofipod.data.repo.RefreshResult
import com.kofikodr.kofipod.data.repo.RemoteEpisodeCache
import com.kofikodr.kofipod.data.repo.SettingsRepository
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.diagnostics.NoOpTelemetry
import com.kofikodr.kofipod.downloads.DownloadEngineApi
import com.kofikodr.kofipod.downloads.DownloadJob
import com.kofikodr.kofipod.downloads.DownloadProgress
import com.kofikodr.kofipod.network.NetworkMonitor
import com.kofikodr.kofipod.network.NetworkType
import com.kofikodr.kofipod.pkm.MarkdownDocument
import com.kofikodr.kofipod.pkm.MarkdownFormatterImpl
import com.kofikodr.kofipod.pkm.PkmExportCoordinator
import com.kofikodr.kofipod.pkm.PkmExportDeps
import com.kofikodr.kofipod.pkm.PkmExportRequest
import com.kofikodr.kofipod.pkm.connections.ConnectionKind
import com.kofikodr.kofipod.pkm.connections.ExportLogEntry
import com.kofikodr.kofipod.pkm.connections.ExportLogRepository
import com.kofikodr.kofipod.pkm.sinks.ExportSink
import com.kofikodr.kofipod.pkm.sinks.ExportSinkResult
import com.kofikodr.kofipod.pkm.sinks.SinkRegistry
import com.kofikodr.kofipod.playback.PlayableEpisode
import com.kofikodr.kofipod.playback.Player
import com.kofikodr.kofipod.playback.PlayerState
import com.kofikodr.kofipod.pro.BillingClientPort
import com.kofikodr.kofipod.pro.EntitlementCache
import com.kofikodr.kofipod.pro.PaywallRouter
import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.pro.ProEntitlementRepository
import com.kofikodr.kofipod.share.Sharer
import com.kofikodr.kofipod.snippets.FileSizer
import com.kofikodr.kofipod.snippets.Snippet
import com.kofikodr.kofipod.snippets.SnippetRepository
import com.kofikodr.kofipod.testing.inMemoryDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for [EpisodeDetailViewModel.togglePlay].
 *
 * The headline test — [togglePlay_startsPlaybackSynchronously] — pins the fix for the bug
 * where tapping "Play episode" did nothing: `togglePlay()` started playback inside
 * `viewModelScope.launch{}`, and the screen's `onPlay` handler immediately navigated to the
 * player (`onOpenPlayer()`), which tore down the ViewModel scope before the launched
 * coroutine ran `player.play()`. The episode never played and the player showed a stale item.
 *
 * The test uses [StandardTestDispatcher] as Main: a `viewModelScope.launch{}` body is only
 * QUEUED (not executed) until the scheduler is advanced — exactly the window in which the
 * real navigation cancelled the scope. So calling `togglePlay()` and asserting `play()` was
 * invoked WITHOUT advancing the scheduler fails on the buggy (deferred) code and passes only
 * when playback is started synchronously. This mirrors the existing
 * `SmartPlaylistDetailViewModelTest.deleteUsesAppScope_notViewModelScope` regression guard.
 *
 * All of [EpisodeDetailViewModel]'s collaborators are real (in-memory DB) or hand-written
 * fakes — the ViewModel under test is never mocked.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeDetailViewModelTest {
    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun runVmTest(block: suspend TestScope.() -> Unit) =
        runTest {
            // StandardTestDispatcher (NOT Unconfined): a deferred launch{} stays queued until
            // advanced, which is what lets this test distinguish synchronous play from a
            // scope-cancellable launch.
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            block()
        }

    private data class Harness(
        val vm: EpisodeDetailViewModel,
        val player: FakePlayer,
        val downloads: DownloadRepository,
    )

    private fun TestScope.harness(
        initialPlayerState: PlayerState = PlayerState(),
        // When true, the DB-backed episode flow resolves the episode (an in-library /
        // subscribed episode); when false, the episode exists only in RemoteEpisodeCache
        // (the Search → unsubscribed projection). Drives `canDownload` for issue #28.
        persisted: Boolean = false,
        // The episode under test — override to vary fields like the enclosure URL.
        episode: Episode = EPISODE,
    ): Harness {
        val db = inMemoryDatabase()
        // UnconfinedTestDispatcher for the repos' DB-query flows so advanceUntilIdle() eagerly
        // drains every flow emission and populates state.value without manual pumping. Main stays
        // StandardTestDispatcher (set in runVmTest) so viewModelScope.launch{} bodies stay queued —
        // that is the probe for the synchrony regression; do not unify the two.
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val player = FakePlayer(initialPlayerState)

        // Episode + its podcast are supplied via the in-memory RemoteEpisodeCache (the
        // "remote-only" projection path), so no DB rows are needed to populate state.
        val cache = RemoteEpisodeCache().apply { put(listOf(RemoteEpisodeCache.Entry(episode, PODCAST))) }

        val downloads =
            DownloadRepository(
                db = db,
                engine = FakeDownloadEngine(),
                settings = SettingsRepository(db, flowContext = testDispatcher),
                network = FakeNetworkMonitor(),
                scope = scope,
                telemetry = NoOpTelemetry,
                fileChecker = FakeFileChecker(),
                uiEvents = com.kofikodr.kofipod.ui.UiEventBus(),
                queryDispatcher = testDispatcher,
            )
        val pkm =
            PkmExportCoordinator(
                deps = FakePkmExportDeps(),
                formatter = MarkdownFormatterImpl(),
                sinks = SinkRegistry(emptyMap()),
                exportLog = FakeExportLog(),
                scheduler = FakePkmExportScheduler(),
                appScope = scope,
                clipboardSink = FakeExportSink(),
                shareFileSink = FakeExportSink(),
            )
        val vm =
            EpisodeDetailViewModel(
                episodeId = EPISODE_ID,
                episodes = FakeEpisodeSource(episode = if (persisted) episode else null),
                library = LibraryRepository(db, queryDispatcher = testDispatcher),
                playback = PlaybackRepository(db, queryDispatcher = testDispatcher),
                downloads = downloads,
                player = player,
                sharer = FakeSharer(),
                chapters = ChaptersRepository(db, noopHttpClient(), queryDispatcher = testDispatcher),
                aiConfig =
                    AiConfigRepository(
                        keyVault = FakeKeyVault(),
                        settings = SettingsRepository(db, flowContext = testDispatcher),
                        appScope = scope,
                    ),
                bookmarkRepo = BookmarkRepository(db),
                snippetRepo = SnippetRepository(db),
                fileSizer = FileSizer(),
                pkmExport = pkm,
                paywallRouter = PaywallRouter(),
                pro =
                    ProEntitlementRepository(
                        cache = FakeEntitlementCache(),
                        port = FakeBillingClientPort(),
                        appScope = scope,
                        reviewerUnlockHash = "",
                    ),
                remoteCache = cache,
            )
        return Harness(vm, player, downloads)
    }

    @Test
    fun togglePlay_startsPlaybackSynchronously() =
        runVmTest {
            val h = harness()
            val collector = launch { h.vm.state.collect {} } // make WhileSubscribed upstream hot
            advanceUntilIdle() // populate state.value (all flows routed to the test scheduler)

            val s = h.vm.state.value
            assertEquals(EPISODE_ID, s.episode?.id, "precondition: episode loaded into state")
            assertEquals(PODCAST_ID, s.podcast?.id, "precondition: podcast loaded into state")
            assertFalse(s.isCurrentEpisode, "precondition: this episode is not already the player's current item")

            h.vm.togglePlay()
            // Deliberately NOT advancing the scheduler here. A viewModelScope.launch{} body
            // would still be queued (and, in production, cancelled by the navigation that
            // immediately follows). Synchronous play is the only thing that has run by now.
            assertEquals(
                1,
                h.player.playCalls.size,
                "togglePlay must call player.play() exactly once, synchronously. Zero calls means " +
                    "either (a) playback was deferred to viewModelScope.launch{} and cancelled by the " +
                    "navigation that immediately follows (the original bug), or (b) resolvedSourceUrl " +
                    "returned null (a source-URL regression — check FakeFileChecker / DB state).",
            )
            val played = h.player.playCalls.single()
            assertEquals(EPISODE_ID, played.episodeId)
            assertEquals(ENCLOSURE_URL, played.sourceUrl, "streaming episode plays from its enclosure URL")
            assertEquals(PODCAST_TITLE, played.podcastTitle)
            assertEquals(PODCAST_ARTWORK, played.artworkUrl, "blank episode art falls back to podcast art")
            assertEquals(3, played.episodeNumber)
            assertEquals(0L, played.startPositionMs, "no prior playback state → start from the beginning")

            collector.cancel()
        }

    @Test
    fun togglePlay_whenAlreadyPlayingThisEpisode_pauses() =
        runVmTest {
            val h = harness(initialPlayerState = PlayerState(episodeId = EPISODE_ID, isPlaying = true))
            val collector = launch { h.vm.state.collect {} }
            advanceUntilIdle()

            assertTrue(h.vm.state.value.isCurrentEpisode)
            h.vm.togglePlay()

            assertEquals(1, h.player.pauseCalls, "playing + current episode → pause")
            assertEquals(0, h.player.resumeCalls, "must not resume when already playing")
            assertEquals(0, h.player.playCalls.size, "must not re-load the media item")
            collector.cancel()
        }

    @Test
    fun togglePlay_whenCurrentEpisodePaused_resumes() =
        runVmTest {
            val h = harness(initialPlayerState = PlayerState(episodeId = EPISODE_ID, isPlaying = false))
            val collector = launch { h.vm.state.collect {} }
            advanceUntilIdle()

            assertTrue(h.vm.state.value.isCurrentEpisode)
            h.vm.togglePlay()

            assertEquals(1, h.player.resumeCalls, "paused + current episode → resume")
            assertEquals(0, h.player.pauseCalls, "must not pause when resuming")
            assertEquals(0, h.player.playCalls.size, "must not re-load the media item")
            collector.cancel()
        }

    @Test
    fun download_remoteOnlyEpisode_isNoOp_andDoesNotWriteAnOrphanRow() =
        runVmTest {
            // The episode renders from RemoteEpisodeCache (Search → unsubscribed) and has an
            // enclosure URL, but no persisted Episode row exists. The old code enabled
            // Download on enclosure presence alone and enqueued a Download whose episodeId
            // FK dangled — rejected (FK on) or an orphan the Downloads list's INNER JOIN
            // Episode hides (FK off). The gate must suppress it (issue #28).
            val h = harness(persisted = false)
            val collector = launch { h.vm.state.collect {} }
            advanceUntilIdle()

            assertEquals(EPISODE_ID, h.vm.state.value.episode?.id, "precondition: episode is displayed")
            assertTrue(h.vm.state.value.episode!!.enclosureUrl.isNotBlank(), "precondition: has an enclosure URL")
            assertFalse(h.vm.state.value.canDownload, "a remote-only episode must not offer Download")

            h.vm.download()
            advanceUntilIdle()

            assertNull(
                h.downloads.forEpisodeFlow(EPISODE_ID).first(),
                "download() on a remote-only episode must not write a Download row",
            )
            collector.cancel()
        }

    @Test
    fun download_persistedEpisode_enqueuesNormally() =
        runVmTest {
            // The complement: a persisted (in-library) episode with an enclosure must still
            // offer Download and enqueue a row, so the #28 gate doesn't over-block.
            val h = harness(persisted = true)
            val collector = launch { h.vm.state.collect {} }
            advanceUntilIdle()

            assertTrue(h.vm.state.value.canDownload, "a persisted episode with an enclosure must offer Download")

            h.vm.download()
            advanceUntilIdle()

            assertNotNull(
                h.downloads.forEpisodeFlow(EPISODE_ID).first(),
                "download() on a persisted episode must enqueue a Download row",
            )
            collector.cancel()
        }

    @Test
    fun download_persistedEpisodeWithBlankEnclosure_isNoOp() =
        runVmTest {
            // Completes the canDownload truth table: persisted but no enclosure URL (some
            // feeds omit it on certain episode types) must still gate Download off — there's
            // nothing to fetch. Guards against a regression that drops the isNotBlank() check.
            val h = harness(persisted = true, episode = EPISODE.copy(enclosureUrl = ""))
            val collector = launch { h.vm.state.collect {} }
            advanceUntilIdle()

            assertFalse(h.vm.state.value.canDownload, "a persisted episode with no enclosure must not offer Download")

            h.vm.download()
            advanceUntilIdle()

            assertNull(
                h.downloads.forEpisodeFlow(EPISODE_ID).first(),
                "download() with a blank enclosure must not write a row",
            )
            collector.cancel()
        }

    private companion object {
        const val EPISODE_ID = "ep-1"
        const val PODCAST_ID = "pod-1"
        const val PODCAST_TITLE = "Test Podcast"
        const val PODCAST_ARTWORK = "https://art/pod.jpg"
        const val ENCLOSURE_URL = "https://cdn/ep-1.mp3"

        val PODCAST =
            Podcast(
                id = PODCAST_ID,
                title = PODCAST_TITLE,
                author = "Author",
                description = "desc",
                artworkUrl = PODCAST_ARTWORK,
                feedUrl = "https://feed",
                listId = null,
                autoDownloadEnabled = 0L,
                notifyNewEpisodesEnabled = 1L,
                lastCheckedAt = null,
                addedAt = 0L,
                primaryCategory = "",
                lastSeenAt = null,
            )

        val EPISODE =
            Episode(
                id = EPISODE_ID,
                podcastId = PODCAST_ID,
                guid = "guid-1",
                title = "Episode One",
                description = "ep desc",
                publishedAt = 0L,
                durationSec = 600L,
                enclosureUrl = ENCLOSURE_URL,
                enclosureMimeType = "audio/mpeg",
                fileSizeBytes = 0L,
                seasonNumber = null,
                episodeNumber = 3L,
                // blank episode art → play() should fall back to the podcast's artwork
                imageUrl = "",
                chaptersUrl = null,
                transcriptUrl = null,
            )

        fun noopHttpClient(): HttpClient = HttpClient(MockEngine { respondError(HttpStatusCode.NotFound) })
    }
}

/** Records playback calls; lets the test assert synchronous invocation. */
private class FakePlayer(initial: PlayerState = PlayerState()) : Player {
    val playCalls = mutableListOf<PlayableEpisode>()
    var pauseCalls = 0
        private set
    var resumeCalls = 0
        private set

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    override val audioLevels: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)).asStateFlow()

    override fun play(episode: PlayableEpisode) {
        playCalls += episode
    }

    override fun pause() {
        pauseCalls++
    }

    override fun resume() {
        resumeCalls++
    }

    override fun seekTo(ms: Long) = Unit

    override fun setSpeed(speed: Float) = Unit

    override fun skipForward() = Unit

    override fun skipBack() = Unit

    override fun setSleepTimer(ms: Long?) = Unit

    override fun stop() = Unit

    override fun release() = Unit
}

private class FakeSharer : Sharer {
    override fun shareText(
        title: String,
        text: String,
    ) = Unit

    override fun shareFile(
        title: String,
        path: String,
        mimeType: String,
        captionText: String?,
    ) = Unit
}

private class FakeEpisodeSource(
    // Null models a remote-only episode (no persisted DB row — supplied via
    // RemoteEpisodeCache instead); non-null models an in-library/subscribed episode.
    private val episode: Episode? = null,
) : EpisodeSource {
    override fun episodeFlow(episodeId: String): Flow<Episode?> = flowOf(episode)

    override fun episodesFlow(podcastId: String): Flow<List<Episode>> = flowOf(emptyList())

    override fun newEpisodeCountsFlow(): Flow<Map<String, Int>> = flowOf(emptyMap())

    override suspend fun refresh(
        podcastId: String,
        feedId: Long,
        nowMillis: Long,
    ): RefreshResult = RefreshResult(insertedEpisodes = emptyList(), totalRemote = 0)
}

private class FakeDownloadEngine : DownloadEngineApi {
    private val _events = MutableSharedFlow<DownloadProgress>()
    override val events: SharedFlow<DownloadProgress> = _events.asSharedFlow()

    override fun enqueue(job: DownloadJob) = Unit

    override fun cancel(episodeId: String) = Unit

    override fun delete(episodeId: String) = Unit
}

private class FakeNetworkMonitor : NetworkMonitor {
    override val type: StateFlow<NetworkType> = MutableStateFlow(NetworkType.Wifi).asStateFlow()
}

private class FakeFileChecker : com.kofikodr.kofipod.snippets.FileCheckerApi {
    override fun exists(path: String): Boolean = false
}

private class FakeKeyVault : KeyVault {
    override suspend fun get(): String? = null

    override suspend fun set(value: String) = Unit

    override suspend fun clear() = Unit
}

private class FakeEntitlementCache : EntitlementCache {
    override suspend fun read(): ProEntitlement? = null

    override suspend fun write(entitlement: ProEntitlement) = Unit

    override suspend fun isReviewerUnlocked(): Boolean = false

    override suspend fun setReviewerUnlocked(unlocked: Boolean) = Unit

    override suspend fun clear() = Unit
}

private class FakeBillingClientPort : BillingClientPort {
    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun queryEntitlement(): Result<ProEntitlement> = Result.success(ProEntitlement.Free)

    override suspend fun queryDisplayPrice(productId: String): Result<String?> = Result.success(null)

    override suspend fun launchPurchase(productId: String): Result<ProEntitlement> = Result.success(ProEntitlement.Free)

    override suspend fun restorePurchases(): Result<ProEntitlement> = Result.success(ProEntitlement.Free)

    override fun close() = Unit
}

private class FakePkmExportScheduler : PkmExportScheduler {
    override fun enqueue() = Unit
}

private class FakePkmExportDeps : PkmExportDeps {
    override suspend fun snippetById(id: String): Snippet? = null

    override suspend fun bookmarkById(id: String): Bookmark? = null

    override suspend fun summaryFor(episodeId: String): AiSummary? = null

    override fun episode(id: String): Episode? = null

    override fun podcast(id: String): Podcast? = null
}

private class FakeExportLog : ExportLogRepository {
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
        externalId: String?,
        nowMs: Long,
    ) = Unit

    override suspend fun markFailed(
        itemKind: String,
        itemId: String,
        destinationKind: ConnectionKind,
        externalId: String?,
        message: String,
        nowMs: Long,
    ) = Unit

    override suspend fun deleteByItem(
        itemKind: String,
        itemId: String,
    ) = Unit
}

private class FakeExportSink : ExportSink {
    override suspend fun export(
        document: MarkdownDocument,
        request: PkmExportRequest,
        priorExternalId: String?,
    ): ExportSinkResult = ExportSinkResult.Success(externalId = null)
}

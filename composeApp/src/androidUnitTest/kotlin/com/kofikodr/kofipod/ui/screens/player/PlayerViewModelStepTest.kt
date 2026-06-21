// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.player

import com.kofikodr.kofipod.bookmarks.BookmarkComposer
import com.kofikodr.kofipod.data.repo.DownloadRepository
import com.kofikodr.kofipod.data.repo.EpisodeSource
import com.kofikodr.kofipod.data.repo.PlaybackRepository
import com.kofikodr.kofipod.data.repo.RefreshResult
import com.kofikodr.kofipod.data.repo.SettingsRepository
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.diagnostics.NoOpTelemetry
import com.kofikodr.kofipod.downloads.DownloadEngineApi
import com.kofikodr.kofipod.downloads.DownloadJob
import com.kofikodr.kofipod.downloads.DownloadProgress
import com.kofikodr.kofipod.network.NetworkMonitor
import com.kofikodr.kofipod.network.NetworkType
import com.kofikodr.kofipod.playback.PlayableEpisode
import com.kofikodr.kofipod.playback.Player
import com.kofikodr.kofipod.playback.PlayerState
import com.kofikodr.kofipod.pro.BillingClientPort
import com.kofikodr.kofipod.pro.EntitlementCache
import com.kofikodr.kofipod.pro.PaywallRouter
import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.pro.ProEntitlementRepository
import com.kofikodr.kofipod.share.Sharer
import com.kofikodr.kofipod.snippets.SnippetRepository
import com.kofikodr.kofipod.testing.inMemoryDatabase
import com.kofikodr.kofipod.ui.UiEventBus
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

/**
 * Regression tests for [PlayerViewModel.prev]/[PlayerViewModel.next] (issue #20).
 *
 * The bug: `step()` built the next [PlayableEpisode] with `artworkUrl = p.artworkUrl`,
 * where `p` is the *currently playing* episode's state rather than the `target` episode
 * being stepped to. For shows with per-episode cover art, the media notification, lock
 * screen, and Android Auto then displayed the *previous* episode's artwork after a
 * prev/next step. The fix mirrors [com.kofikodr.kofipod.ui.screens.detail.EpisodeDetailViewModel]:
 * `target.imageUrl.ifBlank { p.artworkUrl }`.
 *
 * These are behavioral tests against the real [PlayerViewModel] with hand-written fakes /
 * in-memory repos — the ViewModel under test is never mocked. [FakePlayer] records the
 * [PlayableEpisode] handed to `play()` so the test can assert which artwork was chosen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerViewModelStepTest {
    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun runVmTest(block: suspend TestScope.() -> Unit) =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            block()
        }

    private data class Harness(
        val vm: PlayerViewModel,
        val player: FakePlayer,
    )

    /**
     * Builds a [PlayerViewModel] whose player is already on [currentEpisode] (so the
     * `episodesForCurrent` list resolves and `step()` can find the current index), with
     * [episodes] forming the prev/next neighbourhood for that podcast.
     */
    private fun TestScope.harness(
        currentEpisode: Episode,
        episodes: List<Episode>,
        currentArtwork: String,
    ): Harness {
        val db = inMemoryDatabase()
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        val scope = CoroutineScope(testDispatcher)
        val player =
            FakePlayer(
                PlayerState(
                    episodeId = currentEpisode.id,
                    podcastId = currentEpisode.podcastId,
                    artworkUrl = currentArtwork,
                    isPlaying = true,
                ),
            )
        val downloads =
            DownloadRepository(
                db = db,
                engine = FakeDownloadEngine(),
                settings = SettingsRepository(db, flowContext = testDispatcher),
                network = FakeNetworkMonitor(),
                scope = scope,
                telemetry = NoOpTelemetry,
                fileChecker = FakeFileChecker(),
                uiEvents = UiEventBus(),
                queryDispatcher = testDispatcher,
            )
        val vm =
            PlayerViewModel(
                player = player,
                playback = PlaybackRepository(db, queryDispatcher = testDispatcher),
                episodes = FakeEpisodeSource(episodesByPodcast = mapOf(currentEpisode.podcastId to episodes)),
                settings = SettingsRepository(db, flowContext = testDispatcher),
                sharer = FakeSharer(),
                downloads = downloads,
                pro =
                    ProEntitlementRepository(
                        cache = FakeEntitlementCache(),
                        port = FakeBillingClientPort(),
                        appScope = scope,
                        reviewerUnlockHash = "",
                    ),
                paywallRouter = PaywallRouter(),
                bookmarks = BookmarkComposer(),
                snippets = SnippetRepository(db),
            )
        return Harness(vm, player)
    }

    @Test
    fun next_usesTargetEpisodeArtwork_notTheCurrentEpisodes() =
        runVmTest {
            val current = episode(id = "ep-1", number = 1, imageUrl = CURRENT_EP_ART)
            val next = episode(id = "ep-2", number = 2, imageUrl = NEXT_EP_ART)
            val h = harness(current, listOf(current, next), currentArtwork = CURRENT_EP_ART)
            val collector = launch { h.vm.state.collect {} } // make Eagerly upstream hot
            advanceUntilIdle()

            h.vm.next()
            advanceUntilIdle()

            val played = h.player.playCalls.single()
            assertEquals("ep-2", played.episodeId, "next() must step to the following episode")
            assertEquals(
                NEXT_EP_ART,
                played.artworkUrl,
                "the media notification artwork must be the stepped-to episode's own cover, " +
                    "not the previously playing episode's (issue #20)",
            )
            collector.cancel()
        }

    @Test
    fun prev_usesTargetEpisodeArtwork_notTheCurrentEpisodes() =
        runVmTest {
            val prev = episode(id = "ep-1", number = 1, imageUrl = PREV_EP_ART)
            val current = episode(id = "ep-2", number = 2, imageUrl = CURRENT_EP_ART)
            val h = harness(current, listOf(prev, current), currentArtwork = CURRENT_EP_ART)
            val collector = launch { h.vm.state.collect {} }
            advanceUntilIdle()

            h.vm.prev()
            advanceUntilIdle()

            val played = h.player.playCalls.single()
            assertEquals("ep-1", played.episodeId, "prev() must step to the preceding episode")
            assertEquals(PREV_EP_ART, played.artworkUrl, "prev() must carry the preceding episode's own cover")
            collector.cancel()
        }

    @Test
    fun next_whenTargetArtworkBlank_fallsBackToCurrentArtwork() =
        runVmTest {
            // A blank per-episode image is the common case (shows that reuse the channel
            // artwork). The fallback must keep showing *something* — the player's current
            // artworkUrl — rather than a blank cover.
            // NOTE: with a blank target image this assertion passes on BOTH the old buggy
            // code (`p.artworkUrl`) and the fix (`target.imageUrl.ifBlank { p.artworkUrl }`),
            // since both evaluate to CURRENT_EP_ART. So this is not a #20 regression
            // discriminator — its value is forward-looking: it pins the `ifBlank { }`
            // fallback contract against a future change that drops it (e.g. unconditional
            // `target.imageUrl`, which would then yield a blank cover and fail here).
            val current = episode(id = "ep-1", number = 1, imageUrl = CURRENT_EP_ART)
            val next = episode(id = "ep-2", number = 2, imageUrl = "")
            val h = harness(current, listOf(current, next), currentArtwork = CURRENT_EP_ART)
            val collector = launch { h.vm.state.collect {} }
            advanceUntilIdle()

            h.vm.next()
            advanceUntilIdle()

            val played = h.player.playCalls.single()
            assertEquals("ep-2", played.episodeId)
            assertEquals(
                CURRENT_EP_ART,
                played.artworkUrl,
                "blank target episode art falls back to the player's current artwork, never blank",
            )
            collector.cancel()
        }

    @Test
    fun prev_whenTargetArtworkBlank_fallsBackToCurrentArtwork() =
        runVmTest {
            // Symmetry with the next() fallback case: step(-1) and step(1) share the same
            // path, so the blank-art fallback must behave identically in both directions.
            val prev = episode(id = "ep-1", number = 1, imageUrl = "")
            val current = episode(id = "ep-2", number = 2, imageUrl = CURRENT_EP_ART)
            val h = harness(current, listOf(prev, current), currentArtwork = CURRENT_EP_ART)
            val collector = launch { h.vm.state.collect {} }
            advanceUntilIdle()

            h.vm.prev()
            advanceUntilIdle()

            val played = h.player.playCalls.single()
            assertEquals("ep-1", played.episodeId)
            assertEquals(
                CURRENT_EP_ART,
                played.artworkUrl,
                "blank target episode art falls back to the player's current artwork in the prev direction too",
            )
            collector.cancel()
        }

    @Test
    fun next_atListTail_isNoOp() =
        runVmTest {
            // step() guards with `list.getOrNull(idx + direction) ?: return`; stepping past
            // the last episode must not load anything (no wrong-artwork media item, no crash).
            val current = episode(id = "ep-1", number = 1, imageUrl = CURRENT_EP_ART)
            val h = harness(current, listOf(current), currentArtwork = CURRENT_EP_ART)
            val collector = launch { h.vm.state.collect {} }
            advanceUntilIdle()

            h.vm.next()
            advanceUntilIdle()

            assertEquals(0, h.player.playCalls.size, "next() at the tail must not load a media item")
            collector.cancel()
        }

    @Test
    fun prev_atListHead_isNoOp() =
        runVmTest {
            val current = episode(id = "ep-1", number = 1, imageUrl = CURRENT_EP_ART)
            val h = harness(current, listOf(current), currentArtwork = CURRENT_EP_ART)
            val collector = launch { h.vm.state.collect {} }
            advanceUntilIdle()

            h.vm.prev()
            advanceUntilIdle()

            assertEquals(0, h.player.playCalls.size, "prev() at the head must not load a media item")
            collector.cancel()
        }

    private companion object {
        const val PODCAST_ID = "pod-1"
        const val CURRENT_EP_ART = "https://art/current-ep.jpg"
        const val NEXT_EP_ART = "https://art/next-ep.jpg"
        const val PREV_EP_ART = "https://art/prev-ep.jpg"

        fun episode(
            id: String,
            number: Long,
            imageUrl: String,
        ): Episode =
            Episode(
                id = id,
                podcastId = PODCAST_ID,
                guid = "guid-$id",
                title = "Episode $number",
                description = "desc",
                publishedAt = 0L,
                durationSec = 600L,
                enclosureUrl = "https://cdn/$id.mp3",
                enclosureMimeType = "audio/mpeg",
                fileSizeBytes = 0L,
                seasonNumber = null,
                episodeNumber = number,
                imageUrl = imageUrl,
                chaptersUrl = null,
                transcriptUrl = null,
            )
    }
}

/** Records the [PlayableEpisode]s handed to [play] so the test can assert artwork selection. */
private class FakePlayer(initial: PlayerState) : Player {
    val playCalls = mutableListOf<PlayableEpisode>()

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<PlayerState> = _state.asStateFlow()
    override val audioLevels: StateFlow<FloatArray> = MutableStateFlow(FloatArray(0)).asStateFlow()

    override fun play(episode: PlayableEpisode) {
        playCalls += episode
    }

    override fun pause() = Unit

    override fun resume() = Unit

    override fun seekTo(ms: Long) = Unit

    override fun setSpeed(speed: Float) = Unit

    override fun skipForward() = Unit

    override fun skipBack() = Unit

    override fun setSleepTimer(ms: Long?) = Unit

    override fun stop() = Unit

    override fun release() = Unit
}

private class FakeEpisodeSource(
    private val episodesByPodcast: Map<String, List<Episode>>,
) : EpisodeSource {
    override fun episodeFlow(episodeId: String): Flow<Episode?> = flowOf(null)

    override fun episodesFlow(podcastId: String): Flow<List<Episode>> = flowOf(episodesByPodcast[podcastId].orEmpty())

    override fun newEpisodeCountsFlow(): Flow<Map<String, Int>> = flowOf(emptyMap())

    override suspend fun refresh(
        podcastId: String,
        feedId: Long,
        nowMillis: Long,
    ): RefreshResult = RefreshResult(insertedEpisodes = emptyList(), totalRemote = 0)
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

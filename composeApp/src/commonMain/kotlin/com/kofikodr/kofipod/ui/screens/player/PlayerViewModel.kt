// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.bookmarks.BookmarkComposer
import com.kofikodr.kofipod.data.repo.DownloadRepository
import com.kofikodr.kofipod.data.repo.EpisodeSource
import com.kofikodr.kofipod.data.repo.PlaybackRepository
import com.kofikodr.kofipod.data.repo.SettingsRepository
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.playback.PlayableEpisode
import com.kofikodr.kofipod.playback.Player
import com.kofikodr.kofipod.playback.PlayerState
import com.kofikodr.kofipod.pro.PaywallRouter
import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.pro.ProEntitlementRepository
import com.kofikodr.kofipod.share.Sharer
import com.kofikodr.kofipod.snippets.SnippetRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class PlayerUiState(
    val player: PlayerState = PlayerState(),
    val hasPrev: Boolean = false,
    val hasNext: Boolean = false,
    val skipForwardSec: Int = 30,
    val skipBackSec: Int = 10,
    val toast: String? = null,
)

class PlayerViewModel(
    private val player: Player,
    private val playback: PlaybackRepository,
    private val episodes: EpisodeSource,
    private val settings: SettingsRepository,
    private val sharer: Sharer,
    private val downloads: DownloadRepository,
    private val pro: ProEntitlementRepository,
    private val paywallRouter: PaywallRouter,
    private val bookmarks: BookmarkComposer,
    private val snippets: SnippetRepository,
) : ViewModel() {
    private val toast = MutableStateFlow<String?>(null)

    val audioLevels: StateFlow<FloatArray> = player.audioLevels

    /**
     * Current Pro entitlement state delegated from [ProEntitlementRepository]. Collected by
     * [PlayerActionStrip] to conditionally render the PRO badge on each chip.
     */
    val entitlement: StateFlow<ProEntitlement> = pro.state

    /**
     * True once the user has dismissed the NEW coachmark banner at least once.
     * Defaults to `true` (dismissed) until the first DB read resolves, to prevent
     * a banner flash on cold start before the preference is known.
     */
    val isProTipDismissed: StateFlow<Boolean> =
        settings.proTipDismissedAt()
            .map { it != null }
            .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val episodesForCurrent: StateFlow<List<Episode>> =
        player.state
            .map { it.podcastId }
            .distinctUntilChanged()
            .flatMapLatest { pid ->
                if (pid.isBlank()) flowOf(emptyList()) else episodes.episodesFlow(pid)
            }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<PlayerUiState> =
        combine(
            player.state,
            episodesForCurrent,
            settings.skipForwardSeconds(),
            settings.skipBackSeconds(),
            toast,
        ) { p, eps, fwd, back, t ->
            val idx = eps.indexOfFirst { it.id == p.episodeId }
            PlayerUiState(
                player = p,
                hasPrev = idx > 0,
                hasNext = idx in 0 until eps.lastIndex,
                skipForwardSec = fwd,
                skipBackSec = back,
                toast = t,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PlayerUiState())

    fun togglePlayPause() {
        val p = state.value.player
        if (p.isPlaying) player.pause() else player.resume()
    }

    fun seekTo(ms: Long) = player.seekTo(ms)

    fun skipForward() {
        val sec = state.value.skipForwardSec
        val cur = state.value.player.positionMs
        val target =
            (cur + sec * 1000L).coerceAtMost(
                state.value.player.durationMs.takeIf { it > 0 } ?: (cur + sec * 1000L),
            )
        player.seekTo(target)
    }

    fun skipBack() {
        val sec = state.value.skipBackSec
        val target = (state.value.player.positionMs - sec * 1000L).coerceAtLeast(0L)
        player.seekTo(target)
    }

    fun prev() = step(-1)

    fun next() = step(1)

    private fun step(direction: Int) {
        val p = state.value.player
        val list = episodesForCurrent.value
        val idx = list.indexOfFirst { it.id == p.episodeId }
        if (idx < 0) return
        val target = list.getOrNull(idx + direction) ?: return
        if (target.enclosureUrl.isBlank()) return
        viewModelScope.launch {
            val sourceUrl = downloads.resolvedSourceUrl(target.id, target.enclosureUrl) ?: return@launch
            val startMs = playback.positionFor(target.id)
            player.play(
                PlayableEpisode(
                    episodeId = target.id,
                    podcastId = p.podcastId,
                    podcastTitle = p.podcastTitle,
                    title = target.title,
                    // Use the stepped-to episode's own cover, falling back to the
                    // current episode's artwork only when blank. Mirrors
                    // EpisodeDetailViewModel.togglePlay()/seekToChapter(); `p` is the
                    // *previous* episode's state, so `p.artworkUrl` alone left shows
                    // with per-episode art displaying the wrong cover on the media
                    // notification / lock screen / Android Auto (issue #20).
                    artworkUrl = target.imageUrl.ifBlank { p.artworkUrl },
                    sourceUrl = sourceUrl,
                    startPositionMs = startMs,
                    episodeNumber = target.episodeNumber?.takeIf { it in 1..Int.MAX_VALUE }?.toInt(),
                ),
            )
        }
    }

    fun cycleSpeed() {
        val current = state.value.player.speed
        val next = SPEED_STEPS.firstOrNull { it > current + SPEED_EPSILON } ?: SPEED_STEPS.first()
        player.setSpeed(next)
    }

    fun setSleepTimer(minutes: Int?) {
        val ms = minutes?.let { it * 60_000L }
        player.setSleepTimer(ms)
        if (minutes != null) flashToast("Sleep timer: $minutes min")
    }

    fun share() {
        val p = state.value.player
        val id = p.episodeId ?: return
        val link = "https://podcastindex.org/podcast/${p.podcastId}?episode=$id"
        sharer.shareText(
            title = p.title,
            text = "${p.title} — ${p.podcastTitle}\n$link",
        )
    }

    fun markAsPlayed() {
        val p = state.value.player
        val id = p.episodeId ?: return
        viewModelScope.launch {
            playback.markCompleted(
                episodeId = id,
                nowMillis = Clock.System.now().toEpochMilliseconds(),
                currentDurationMs = p.durationMs,
            )
            flashToast("Marked as played")
        }
    }

    fun dismissToast() {
        toast.value = null
    }

    /**
     * Pro users get a quick-add composer pre-filled with the current player
     * position and episode metadata. Free / Unknown users get routed to the
     * Paywall sheet via [PaywallRouter]. The composer is hoisted at AppShell
     * (see [BookmarkComposer] KDoc) so navigation away from the player does
     * not dismiss it.
     */
    fun onBookmarkTapped() {
        when (pro.state.value) {
            is ProEntitlement.Pro -> {
                val p = state.value.player
                val episodeId = p.episodeId ?: return
                if (p.podcastId.isBlank()) return
                bookmarks.requestQuickAdd(
                    episodeId = episodeId,
                    podcastId = p.podcastId,
                    episodeTitle = p.title,
                    podcastTitle = p.podcastTitle,
                    timestampMs = p.positionMs,
                )
            }
            ProEntitlement.Free,
            ProEntitlement.Unknown,
            -> paywallRouter.requestPaywall("paywall_bookmark")
        }
    }

    /**
     * One-shot navigation channel for the freshly-created snippet draft id.
     * `extraBufferCapacity = 1` keeps `tryEmit` non-suspending while still
     * dropping repeat taps that fire before [PlayerScreen] collects.
     */
    private val _snippetEditorRoute = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snippetEditorRoute: SharedFlow<String> = _snippetEditorRoute

    /**
     * Pro-gated. On Pro: build a "snip last 60s" draft via [SnippetRepository]
     * and emit the new draft id so [PlayerScreen] can navigate to the editor.
     * On Free / Unknown: open the paywall sheet via [PaywallRouter].
     */
    fun onSnipTapped() {
        when (pro.state.value) {
            is ProEntitlement.Pro -> {
                val p = state.value.player
                val episodeId = p.episodeId ?: return
                if (p.podcastId.isBlank()) return
                viewModelScope.launch {
                    val id =
                        snippets.createDraftFromPlayer(
                            episodeId = episodeId,
                            podcastId = p.podcastId,
                            playerPositionMs = p.positionMs,
                            episodeDurationMs = p.durationMs,
                            episodeTitle = p.title,
                            nowMs = Clock.System.now().toEpochMilliseconds(),
                        )
                    _snippetEditorRoute.tryEmit(id)
                }
            }
            ProEntitlement.Free,
            ProEntitlement.Unknown,
            -> paywallRouter.requestPaywall("paywall_snippet")
        }
    }

    /**
     * Persists the current epoch-ms as the dismissal timestamp so the NEW coachmark
     * is never shown again on this device.
     */
    fun dismissProTip() {
        settings.setProTipDismissedAt(Clock.System.now().toEpochMilliseconds())
    }

    private fun flashToast(message: String) {
        toast.value = message
    }

    companion object {
        private val SPEED_STEPS = listOf(0.8f, 1.0f, 1.1f, 1.2f, 1.5f, 2.0f)
        private const val SPEED_EPSILON = 0.05f
    }
}

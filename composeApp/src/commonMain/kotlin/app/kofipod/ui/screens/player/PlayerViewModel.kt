// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.data.repo.PlaybackRepository
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.db.Episode
import app.kofipod.playback.KofipodPlayer
import app.kofipod.playback.PlayableEpisode
import app.kofipod.playback.PlayerState
import app.kofipod.share.Sharer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val player: KofipodPlayer,
    private val playback: PlaybackRepository,
    private val episodes: EpisodeSource,
    private val settings: SettingsRepository,
    private val sharer: Sharer,
    private val downloads: DownloadRepository,
) : ViewModel() {
    private val toast = MutableStateFlow<String?>(null)

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
        val sourceUrl = downloads.resolvedSourceUrl(target.id, target.enclosureUrl) ?: return
        val startMs = playback.positionFor(target.id)
        player.play(
            PlayableEpisode(
                episodeId = target.id,
                podcastId = p.podcastId,
                podcastTitle = p.podcastTitle,
                title = target.title,
                artworkUrl = p.artworkUrl,
                sourceUrl = sourceUrl,
                startPositionMs = startMs,
                episodeNumber = target.episodeNumber?.takeIf { it in 1..Int.MAX_VALUE }?.toInt(),
            ),
        )
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

    private fun flashToast(message: String) {
        toast.value = message
    }

    companion object {
        private val SPEED_STEPS = listOf(0.8f, 1.0f, 1.1f, 1.2f, 1.5f, 2.0f)
        private const val SPEED_EPSILON = 0.05f
    }
}

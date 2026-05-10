// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.askgemini

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.ai.DiscussRepository
import com.kofikodr.kofipod.ai.DiscussUiState
import com.kofikodr.kofipod.data.repo.DownloadRepository
import com.kofikodr.kofipod.data.repo.EpisodeSource
import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.data.repo.PlaybackRepository
import com.kofikodr.kofipod.playback.KofipodPlayer
import com.kofikodr.kofipod.playback.PlayableEpisode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Owns the full-screen Ask Gemini chat for one episode. Two separate state
 * flows so the per-keystroke composer can update without recomposing the
 * entire message list — same separation invariant as
 * [com.kofikodr.kofipod.ui.screens.detail.PodcastDetailViewModel] (cf. CLAUDE.md
 * "Performance-sensitive invariants").
 *
 * The seek dependency surface is wide because citations behave like chapter
 * taps: if the player is already on this episode → seek; if not → start
 * playback at that millisecond. Mirrors
 * [com.kofikodr.kofipod.ui.screens.detail.EpisodeDetailViewModel.seekToChapter].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AskGeminiViewModel(
    private val episodeId: String,
    private val repo: DiscussRepository,
    private val episodes: EpisodeSource,
    private val library: LibraryRepository,
    private val playback: PlaybackRepository,
    private val downloads: DownloadRepository,
    private val player: KofipodPlayer,
) : ViewModel() {
    val state: StateFlow<DiscussUiState> =
        repo.observeFor(episodeId).stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = DiscussUiState.Hidden,
        )

    private val composerInput = MutableStateFlow("")
    val composer: StateFlow<String> = composerInput.asStateFlow()

    private val episodeFlow = episodes.episodeFlow(episodeId)
    private val podcastFlow =
        episodeFlow
            .map { it?.podcastId }
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) flowOf(null) else library.podcastFlow(id) }

    val header: StateFlow<EpisodeHeader> =
        combine(episodeFlow, podcastFlow) { episode, podcast ->
            EpisodeHeader(
                title = episode?.title.orEmpty(),
                podcastTitle = podcast?.title.orEmpty(),
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = EpisodeHeader(title = "", podcastTitle = ""),
        )

    fun onComposerChange(value: String) {
        composerInput.value = value
    }

    /**
     * Submit the composer text. Clears the input optimistically — the
     * repository persists the user turn before the network round-trip starts,
     * so the message lands in the list immediately. A blank submit is dropped
     * inside [DiscussRepository.send].
     */
    fun submit() {
        val text = composerInput.value
        if (text.isBlank()) return
        composerInput.value = ""
        repo.send(episodeId, text)
    }

    /**
     * Submit a quick-prompt or suggestion verbatim. The chip text IS the
     * question — we don't pre-fill the input, we just send it. Composer
     * input is left untouched so a half-typed message survives a chip tap.
     */
    fun submitPreset(text: String) {
        if (text.isBlank()) return
        repo.send(episodeId, text)
    }

    fun clearChat() =
        viewModelScope.launch {
            repo.clearForEpisode(episodeId)
        }

    /**
     * Re-runs the chat call against the most recent user message, without
     * inserting a duplicate user row. Wired to the Retry button on the error
     * bubble. Safe to call when no message has been sent — repo no-ops.
     */
    fun retry() {
        repo.retry(episodeId)
    }

    /**
     * Tap-handler for a citation timestamp. If the player is already on this
     * episode, seek; otherwise resolve the source URL and start playback at
     * the citation point. Returns `true` when a fresh play was kicked so the
     * caller can navigate to the player screen.
     */
    suspend fun seekToCitation(timestampMs: Long): Boolean {
        val playerState = player.state.value
        if (playerState.episodeId == episodeId) {
            player.seekTo(timestampMs)
            if (!playerState.isPlaying) player.resume()
            return false
        }
        val ep = episodeFlow.first() ?: return false
        val pod = library.podcastFlow(ep.podcastId).first() ?: return false
        val sourceUrl = downloads.resolvedSourceUrl(episodeId, ep.enclosureUrl) ?: return false
        // Override the saved position with the citation point so playback
        // starts exactly where the model said the answer came from.
        player.play(
            PlayableEpisode(
                episodeId = episodeId,
                podcastId = pod.id,
                podcastTitle = pod.title,
                title = ep.title,
                artworkUrl = ep.imageUrl.ifBlank { pod.artworkUrl },
                sourceUrl = sourceUrl,
                startPositionMs = timestampMs,
                episodeNumber = ep.episodeNumber?.toInt(),
            ),
        )
        // Touch the cached position too so a later "Resume" picks up the
        // citation point rather than the stale prior position.
        playback.save(
            episodeId = episodeId,
            positionMs = timestampMs,
            durationMs = ep.durationSec * 1000L,
            speed = 1f,
            updatedAt = Clock.System.now().toEpochMilliseconds(),
            episodeTitle = ep.title,
            podcastId = pod.id,
            podcastTitle = pod.title,
            artworkUrl = ep.imageUrl.ifBlank { pod.artworkUrl },
            sourceUrl = ep.enclosureUrl,
            episodeNumber = ep.episodeNumber?.toInt(),
        )
        return true
    }
}

/** Header strip data shown across the top of the Ask Gemini screen. */
data class EpisodeHeader(
    val title: String,
    val podcastTitle: String,
)

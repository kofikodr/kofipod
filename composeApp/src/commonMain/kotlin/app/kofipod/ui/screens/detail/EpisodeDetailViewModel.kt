// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.ChaptersRepository
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.data.repo.PlaybackRepository
import app.kofipod.db.Download
import app.kofipod.db.Episode
import app.kofipod.db.EpisodeChapter
import app.kofipod.db.PlaybackState
import app.kofipod.db.Podcast
import app.kofipod.downloads.DownloadJob
import app.kofipod.downloads.downloadFileName
import app.kofipod.playback.KofipodPlayer
import app.kofipod.playback.PlayableEpisode
import app.kofipod.share.Sharer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class EpisodeDetailUiState(
    val episode: Episode? = null,
    val podcast: Podcast? = null,
    val chapters: List<EpisodeChapter> = emptyList(),
    val isPlayingThis: Boolean = false,
    val isCurrentEpisode: Boolean = false,
    val downloaded: Boolean = false,
    val played: Boolean = false,
    val loading: Boolean = true,
    val error: String? = null,
)

class EpisodeDetailViewModel(
    private val episodeId: String,
    episodes: EpisodeSource,
    private val library: LibraryRepository,
    private val playback: PlaybackRepository,
    private val downloads: DownloadRepository,
    private val player: KofipodPlayer,
    private val sharer: Sharer,
    private val chapters: ChaptersRepository,
) : ViewModel() {
    private val error = MutableStateFlow<String?>(null)

    private val episodeFlow = episodes.episodeFlow(episodeId)
    private val chaptersFlow = chapters.chaptersFlow(episodeId)

    init {
        viewModelScope.launch {
            episodeFlow.collect { ep ->
                val url = ep?.chaptersUrl?.takeIf { it.isNotBlank() } ?: return@collect
                if (chapters.hasCached(episodeId)) return@collect
                chapters.refresh(episodeId, url)
            }
        }
    }

    val state: StateFlow<EpisodeDetailUiState> =
        combine(
            episodeFlow,
            playback.stateFlow(episodeId),
            downloads.forEpisodeFlow(episodeId),
            player.state,
            chaptersFlow,
            error,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val ep = values[0] as Episode?
            val ps = values[1] as PlaybackState?
            val dl = values[2] as Download?
            val playerState = values[3] as app.kofipod.playback.PlayerState
            val chapterRows = values[4] as List<EpisodeChapter>
            val err = values[5] as String?
            val podcast = ep?.podcastId?.let { library.podcastNow(it) }
            EpisodeDetailUiState(
                episode = ep,
                podcast = podcast,
                chapters = chapterRows,
                isPlayingThis = playerState.episodeId == episodeId && playerState.isPlaying,
                isCurrentEpisode = playerState.episodeId == episodeId,
                downloaded = dl.isDownloaded(),
                played = ps.isPlayed(),
                loading = ep == null && err == null,
                error = err,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EpisodeDetailUiState())

    fun togglePlay() {
        val s = state.value
        val ep = s.episode ?: return
        val pod = s.podcast ?: return
        if (s.isCurrentEpisode) {
            if (s.isPlayingThis) player.pause() else player.resume()
            return
        }
        viewModelScope.launch {
            val sourceUrl = downloads.resolvedSourceUrl(episodeId, ep.enclosureUrl) ?: return@launch
            val startMs = playback.positionFor(episodeId)
            player.play(
                PlayableEpisode(
                    episodeId = episodeId,
                    podcastId = pod.id,
                    podcastTitle = pod.title,
                    title = ep.title,
                    artworkUrl = ep.imageUrl.ifBlank { pod.artworkUrl },
                    sourceUrl = sourceUrl,
                    startPositionMs = startMs,
                    episodeNumber = ep.episodeNumber?.toInt(),
                ),
            )
        }
    }

    fun markPlayed() {
        val ep = state.value.episode ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        playback.markCompleted(
            episodeId = episodeId,
            nowMillis = now,
            currentDurationMs = ep.durationSec * 1000L,
        )
    }

    fun deleteDownload() {
        if (!state.value.downloaded) return
        downloads.delete(episodeId)
    }

    fun download() {
        val ep = state.value.episode ?: return
        if (ep.enclosureUrl.isBlank()) return
        if (state.value.downloaded) return
        downloads.enqueue(
            episodeId = ep.id,
            url = ep.enclosureUrl,
            fileName = downloadFileName(ep.id, ep.enclosureMimeType),
            source = DownloadJob.Source.Manual,
        )
    }

    fun seekToChapter(startMs: Long) {
        val s = state.value
        if (s.isCurrentEpisode) {
            player.seekTo(startMs)
            if (!s.isPlayingThis) player.resume()
            return
        }
        val ep = s.episode ?: return
        val pod = s.podcast ?: return
        viewModelScope.launch {
            val sourceUrl = downloads.resolvedSourceUrl(episodeId, ep.enclosureUrl) ?: return@launch
            player.play(
                PlayableEpisode(
                    episodeId = episodeId,
                    podcastId = pod.id,
                    podcastTitle = pod.title,
                    title = ep.title,
                    artworkUrl = ep.imageUrl.ifBlank { pod.artworkUrl },
                    sourceUrl = sourceUrl,
                    startPositionMs = startMs,
                    episodeNumber = ep.episodeNumber?.toInt(),
                ),
            )
        }
    }

    fun share() {
        val ep = state.value.episode ?: return
        val pod = state.value.podcast
        val link = ep.enclosureUrl.ifBlank { pod?.feedUrl.orEmpty() }
        sharer.shareText(
            title = ep.title,
            text = "${ep.title}${pod?.title?.let { " — $it" } ?: ""}\n$link",
        )
    }
}

private fun Download?.isDownloaded(): Boolean = this != null && state == "Completed" && !localPath.isNullOrBlank()

private fun PlaybackState?.isPlayed(): Boolean = this != null && completedAt != null

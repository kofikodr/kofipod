// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.data.repo.DownloadRow
import app.kofipod.data.repo.EpisodesRepository
import app.kofipod.data.repo.PlaybackRepository
import app.kofipod.playback.KofipodPlayer
import app.kofipod.playback.PlayableEpisode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DownloadsUiState(
    val downloading: List<DownloadRow> = emptyList(),
    val queued: List<DownloadRow> = emptyList(),
    val completed: List<DownloadRow> = emptyList(),
    val failed: List<DownloadRow> = emptyList(),
)

class DownloadsViewModel(
    private val repo: DownloadRepository,
    private val episodes: EpisodesRepository,
    private val playback: PlaybackRepository,
    private val player: KofipodPlayer,
) : ViewModel() {
    val state: StateFlow<DownloadsUiState> =
        repo.allWithMeta()
            .map { all ->
                DownloadsUiState(
                    downloading = all.filter { it.state == "Downloading" },
                    queued = all.filter { it.state == "Queued" || it.state == "Paused" },
                    completed = all.filter { it.state == "Completed" },
                    failed = all.filter { it.state == "Failed" },
                )
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUiState())

    fun cancel(episodeId: String) = repo.cancel(episodeId)

    fun delete(episodeId: String) = repo.delete(episodeId)

    fun play(episodeId: String) {
        if (player.state.value.episodeId == episodeId) {
            if (player.state.value.isPlaying) player.pause() else player.resume()
            return
        }
        val row =
            state.value.completed.firstOrNull { it.episodeId == episodeId }
                ?: state.value.downloading.firstOrNull { it.episodeId == episodeId }
                ?: state.value.queued.firstOrNull { it.episodeId == episodeId }
                ?: return
        val ep = episodes.episodeNow(episodeId) ?: return
        viewModelScope.launch {
            val sourceUrl = repo.resolvedSourceUrl(episodeId, ep.enclosureUrl) ?: return@launch
            val startMs = playback.positionFor(episodeId)
            player.play(
                PlayableEpisode(
                    episodeId = episodeId,
                    podcastId = row.podcastId ?: return@launch,
                    podcastTitle = row.podcastTitle ?: "",
                    title = ep.title,
                    artworkUrl = row.artworkUrl ?: "",
                    sourceUrl = sourceUrl,
                    startPositionMs = startMs,
                    episodeNumber = ep.episodeNumber?.toInt(),
                ),
            )
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.api.PodcastIndexApi
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.data.repo.PlaybackRepository
import app.kofipod.data.repo.autoDownloadEnabledBool
import app.kofipod.db.Episode
import app.kofipod.db.Podcast
import app.kofipod.db.PodcastList
import app.kofipod.domain.PodcastSummary
import app.kofipod.domain.toSummary
import app.kofipod.playback.KofipodPlayer
import app.kofipod.playback.PlayableEpisode
import com.mr3y.podcastindex.model.EpisodeFeed
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class DetailUiState(
    val summary: PodcastSummary? = null,
    val inLibrary: Boolean = false,
    val listId: String? = null,
    val autoDownload: Boolean = false,
    val storedEpisodes: List<Episode> = emptyList(),
    val remoteEpisodes: List<EpisodePreview> = emptyList(),
    val lists: List<PodcastList> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class EpisodePreview(
    val id: String,
    val title: String,
    val durationMinutes: Int,
)

class PodcastDetailViewModel(
    private val podcastId: String,
    private val library: LibraryRepository,
    private val episodes: EpisodeSource,
    private val api: PodcastIndexApi,
    private val player: KofipodPlayer,
    private val playback: PlaybackRepository,
) : ViewModel() {

    private val remoteSummary = MutableStateFlow<PodcastSummary?>(null)
    private val remoteEpisodes = MutableStateFlow<List<EpisodePreview>>(emptyList())
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<String?>(null)

    val state: StateFlow<DetailUiState> = combine(
        library.podcastFlow(podcastId),
        episodes.episodesFlow(podcastId),
        library.listsFlow(),
        remoteSummary,
        combine(remoteEpisodes, loading, error) { e, l, err -> Triple(e, l, err) },
    ) { storedPodcast: Podcast?, storedEps, lists, remote, (remoteEps, isLoading, err) ->
        val summary = storedPodcast?.toSummary() ?: remote
        DetailUiState(
            summary = summary,
            inLibrary = storedPodcast != null,
            listId = storedPodcast?.listId,
            autoDownload = storedPodcast?.autoDownloadEnabledBool() ?: false,
            storedEpisodes = storedEps,
            remoteEpisodes = remoteEps,
            lists = lists,
            loading = isLoading,
            error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    init { loadRemote() }

    private fun loadRemote() {
        viewModelScope.launch {
            loading.value = true
            val feedId = podcastId.toLongOrNull()
            if (feedId == null) {
                error.value = "Invalid podcast id"
                loading.value = false
                return@launch
            }
            runCatching {
                val feed = api.podcastByFeedId(feedId)
                remoteSummary.value = feed.toSummary()
                val eps = api.episodesByFeedId(feedId)
                remoteEpisodes.value = eps.map { it.toPreview() }
            }.onFailure { error.value = it.message ?: "Failed to load podcast" }
            loading.value = false
        }
    }

    fun saveToList(listId: String?) {
        val summary = state.value.summary ?: return
        val now = Clock.System.now().toEpochMilliseconds()
        if (!state.value.inLibrary) {
            library.savePodcast(summary, listId, now)
            persistRemoteEpisodes()
        } else {
            library.movePodcastToList(podcastId, listId)
        }
    }

    fun play(episodeId: String) {
        val ep = state.value.storedEpisodes.firstOrNull { it.id == episodeId } ?: return
        if (ep.enclosureUrl.isBlank()) return
        val summary = state.value.summary ?: return
        val startMs = playback.positionFor(episodeId)
        player.play(
            PlayableEpisode(
                episodeId = ep.id,
                podcastTitle = summary.title,
                title = ep.title,
                artworkUrl = summary.artworkUrl,
                sourceUrl = ep.enclosureUrl,
                startPositionMs = startMs,
            ),
        )
    }

    fun toggleAutoDownload(enabled: Boolean) {
        if (!state.value.inLibrary) return
        library.setAutoDownload(podcastId, enabled)
    }

    private fun persistRemoteEpisodes() {
        viewModelScope.launch {
            val feedId = podcastId.toLongOrNull() ?: return@launch
            runCatching { episodes.refresh(podcastId, feedId, Clock.System.now().toEpochMilliseconds()) }
                .onFailure { error.value = it.message ?: "Failed to save episodes" }
        }
    }
}

private fun EpisodeFeed.toPreview(): EpisodePreview = EpisodePreview(
    id = id.toString(),
    title = title,
    durationMinutes = (duration ?: 0) / 60,
)

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.api.PodcastIndexApi
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.data.repo.PlaybackRepository
import app.kofipod.data.repo.autoDownloadEnabledBool
import app.kofipod.db.Download
import app.kofipod.db.Episode
import app.kofipod.db.Podcast
import app.kofipod.db.PodcastList
import app.kofipod.domain.PodcastSummary
import app.kofipod.domain.toSummary
import app.kofipod.downloads.DownloadJob
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
    val downloadStates: Map<String, String> = emptyMap(),
    val lists: List<PodcastList> = emptyList(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class EpisodePreview(
    val id: String,
    val title: String,
    val durationMinutes: Int,
)

private data class RemoteBundle(
    val episodes: List<EpisodePreview>,
    val downloads: List<Download>,
    val loading: Boolean,
    val error: String?,
)

class PodcastDetailViewModel(
    private val podcastId: String,
    private val library: LibraryRepository,
    private val episodes: EpisodeSource,
    private val api: PodcastIndexApi,
    private val player: KofipodPlayer,
    private val playback: PlaybackRepository,
    private val downloads: DownloadRepository,
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
        combine(
            remoteEpisodes,
            downloads.all(),
            loading,
            error,
        ) { e, dls, l, err -> RemoteBundle(e, dls, l, err) },
    ) { storedPodcast: Podcast?, storedEps, lists, remote, bundle ->
        val stored = storedPodcast?.toSummary()
        val summary = when {
            stored != null && remote != null -> stored.copy(
                category = remote.category.ifBlank { stored.category },
                episodeCount = if (remote.episodeCount > 0) remote.episodeCount else stored.episodeCount,
            )
            stored != null -> stored
            else -> remote
        }
        val resolvedCount = if ((summary?.episodeCount ?: 0) == 0 && storedEps.isNotEmpty()) {
            summary?.copy(episodeCount = storedEps.size)
        } else summary
        DetailUiState(
            summary = resolvedCount,
            inLibrary = storedPodcast != null,
            listId = storedPodcast?.listId,
            autoDownload = storedPodcast?.autoDownloadEnabledBool() ?: false,
            storedEpisodes = storedEps,
            remoteEpisodes = bundle.episodes,
            downloadStates = bundle.downloads.associate { it.episodeId to it.state },
            lists = lists,
            loading = bundle.loading,
            error = bundle.error,
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

    fun download(episodeId: String) {
        val ep = state.value.storedEpisodes.firstOrNull { it.id == episodeId } ?: return
        if (ep.enclosureUrl.isBlank()) return
        val ext = ep.enclosureMimeType.substringAfter('/', "mp3").ifBlank { "mp3" }
        downloads.enqueue(
            episodeId = ep.id,
            url = ep.enclosureUrl,
            fileName = "${ep.id}.$ext",
            source = DownloadJob.Source.Manual,
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

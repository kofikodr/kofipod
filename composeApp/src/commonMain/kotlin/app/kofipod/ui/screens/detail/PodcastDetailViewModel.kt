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
import app.kofipod.data.repo.notifyNewEpisodesEnabledBool
import app.kofipod.db.Download
import app.kofipod.db.Episode
import app.kofipod.db.Podcast
import app.kofipod.db.PodcastList
import app.kofipod.domain.PodcastSummary
import app.kofipod.domain.toSummary
import app.kofipod.downloads.DownloadJob
import app.kofipod.playback.KofipodPlayer
import app.kofipod.playback.PlayableEpisode
import app.kofipod.share.Sharer
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
    val notifyNewEpisodes: Boolean = true,
    val storedEpisodes: List<Episode> = emptyList(),
    val remoteEpisodes: List<EpisodePreview> = emptyList(),
    val downloadStates: Map<String, String> = emptyMap(),
    val lists: List<PodcastList> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val episodeDisplayLimit: Int = PodcastIndexApi.PAGE_SIZE,
    val remoteHasMore: Boolean = false,
    val error: String? = null,
    val playingEpisodeId: String? = null,
    val isPlaying: Boolean = false,
    val playbackProgress: Float = 0f,
)

data class EpisodePreview(
    val id: String,
    val title: String,
    val durationMinutes: Int,
    val enclosureUrl: String = "",
    val episodeNumber: Int? = null,
)

class PodcastDetailViewModel(
    private val podcastId: String,
    private val library: LibraryRepository,
    private val episodes: EpisodeSource,
    private val api: PodcastIndexApi,
    private val player: KofipodPlayer,
    private val playback: PlaybackRepository,
    private val downloads: DownloadRepository,
    private val sharer: Sharer,
) : ViewModel() {

    private val remoteSummary = MutableStateFlow<PodcastSummary?>(null)
    private val remoteEpisodes = MutableStateFlow<List<EpisodePreview>>(emptyList())
    private val loading = MutableStateFlow(false)
    private val loadingMore = MutableStateFlow(false)
    private val displayLimit = MutableStateFlow(PodcastIndexApi.PAGE_SIZE)
    private val remoteLimit = MutableStateFlow(PodcastIndexApi.PAGE_SIZE)
    private val error = MutableStateFlow<String?>(null)

    private data class StoredBundle(val podcast: Podcast?, val episodes: List<Episode>, val lists: List<PodcastList>)
    private data class RemoteBundle(
        val summary: PodcastSummary?,
        val episodes: List<EpisodePreview>,
        val limit: Int,
        val downloads: List<Download>,
    )
    private data class UiFlags(val loading: Boolean, val loadingMore: Boolean, val displayLimit: Int, val error: String?)

    val state: StateFlow<DetailUiState> = combine(
        combine(library.podcastFlow(podcastId), episodes.episodesFlow(podcastId), library.listsFlow(), ::StoredBundle),
        combine(remoteSummary, remoteEpisodes, remoteLimit, downloads.all(), ::RemoteBundle),
        combine(loading, loadingMore, displayLimit, error, ::UiFlags),
        player.state,
    ) { stored, remote, flags, playerState ->
        val storedSummary = stored.podcast?.toSummary()
        val merged = when {
            storedSummary != null && remote.summary != null -> storedSummary.copy(
                category = remote.summary.category.ifBlank { storedSummary.category },
                episodeCount = if (remote.summary.episodeCount > 0) remote.summary.episodeCount else storedSummary.episodeCount,
            )
            storedSummary != null -> storedSummary
            else -> remote.summary
        }
        val summary = if ((merged?.episodeCount ?: 0) == 0 && stored.episodes.isNotEmpty()) {
            merged?.copy(episodeCount = stored.episodes.size)
        } else merged
        DetailUiState(
            summary = summary,
            inLibrary = stored.podcast != null,
            listId = stored.podcast?.listId,
            autoDownload = stored.podcast?.autoDownloadEnabledBool() ?: false,
            notifyNewEpisodes = stored.podcast?.notifyNewEpisodesEnabledBool() ?: true,
            storedEpisodes = stored.episodes,
            remoteEpisodes = remote.episodes,
            downloadStates = remote.downloads.associate { it.episodeId to it.state },
            lists = stored.lists,
            loading = flags.loading,
            loadingMore = flags.loadingMore,
            episodeDisplayLimit = flags.displayLimit,
            remoteHasMore = remote.episodes.size >= remote.limit,
            error = flags.error,
            playingEpisodeId = playerState.episodeId,
            isPlaying = playerState.isPlaying,
            playbackProgress = if (playerState.durationMs > 0L) {
                (playerState.positionMs.toFloat() / playerState.durationMs.toFloat()).coerceIn(0f, 1f)
            } else 0f,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    init { loadRemote(loadMore = false) }

    fun loadMoreEpisodes() {
        if (loadingMore.value) return
        if (state.value.inLibrary) {
            val stored = state.value.storedEpisodes.size
            if (displayLimit.value < stored) {
                displayLimit.value = (displayLimit.value + PodcastIndexApi.PAGE_SIZE).coerceAtMost(stored)
            }
            return
        }
        if (!state.value.remoteHasMore) return
        remoteLimit.value = remoteLimit.value + PodcastIndexApi.PAGE_SIZE
        displayLimit.value = remoteLimit.value
        loadRemote(loadMore = true)
    }

    private fun loadRemote(loadMore: Boolean) {
        viewModelScope.launch {
            if (loadMore) loadingMore.value = true else loading.value = true
            val feedId = podcastId.toLongOrNull()
            if (feedId == null) {
                error.value = "Invalid podcast id"
                loading.value = false
                loadingMore.value = false
                return@launch
            }
            runCatching {
                if (!loadMore) {
                    val feed = api.podcastByFeedId(feedId)
                    remoteSummary.value = feed.toSummary()
                }
                val eps = api.episodesByFeedId(feedId, limit = remoteLimit.value)
                remoteEpisodes.value = eps.map { it.toPreview() }
            }.onFailure { error.value = it.message ?: "Failed to load podcast" }
            loading.value = false
            loadingMore.value = false
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
        val current = state.value
        if (current.playingEpisodeId == episodeId) {
            if (current.isPlaying) player.pause() else player.resume()
            return
        }
        val summary = current.summary ?: return
        val stored = current.storedEpisodes.firstOrNull { it.id == episodeId }
        data class PlayFields(val title: String, val url: String, val episodeNumber: Int?)
        val fields = when {
            stored != null && stored.enclosureUrl.isNotBlank() -> PlayFields(
                stored.title,
                stored.enclosureUrl,
                stored.episodeNumber?.toInt(),
            )
            else -> {
                val remote = current.remoteEpisodes.firstOrNull { it.id == episodeId } ?: return
                if (remote.enclosureUrl.isBlank()) return
                PlayFields(remote.title, remote.enclosureUrl, remote.episodeNumber)
            }
        }
        val startMs = playback.positionFor(episodeId)
        player.play(
            PlayableEpisode(
                episodeId = episodeId,
                podcastId = podcastId,
                podcastTitle = summary.title,
                title = fields.title,
                artworkUrl = summary.artworkUrl,
                sourceUrl = fields.url,
                startPositionMs = startMs,
                episodeNumber = fields.episodeNumber,
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

    fun toggleNotifyNewEpisodes(enabled: Boolean) {
        if (!state.value.inLibrary) return
        library.setNotifyNewEpisodes(podcastId, enabled)
    }

    fun sharePodcast() {
        val summary = state.value.summary ?: return
        val link = summary.feedUrl.ifBlank { "https://podcastindex.org/podcast/${summary.id}" }
        sharer.shareText(
            title = summary.title,
            text = "${summary.title} — ${summary.author}\n$link",
        )
    }

    fun shareEpisode(episodeId: String) {
        val summary = state.value.summary ?: return
        val stored = state.value.storedEpisodes.firstOrNull { it.id == episodeId }
        val title = stored?.title
            ?: state.value.remoteEpisodes.firstOrNull { it.id == episodeId }?.title
            ?: return
        val url = stored?.enclosureUrl?.takeIf { it.isNotBlank() }
            ?: state.value.remoteEpisodes.firstOrNull { it.id == episodeId }?.enclosureUrl
            ?: summary.feedUrl
        sharer.shareText(
            title = title,
            text = "$title — ${summary.title}\n$url",
        )
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
    enclosureUrl = enclosureUrl,
    episodeNumber = episode,
)

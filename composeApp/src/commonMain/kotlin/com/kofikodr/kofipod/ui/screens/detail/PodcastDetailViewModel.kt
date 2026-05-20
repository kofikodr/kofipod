// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.data.api.PodcastIndexApi
import com.kofikodr.kofipod.data.net.NetworkErrorHandler
import com.kofikodr.kofipod.data.repo.DownloadRepository
import com.kofikodr.kofipod.data.repo.EpisodeSource
import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.data.repo.PlaybackRepository
import com.kofikodr.kofipod.data.repo.RecentlyViewedRepository
import com.kofikodr.kofipod.data.repo.RemoteEpisodeCache
import com.kofikodr.kofipod.data.repo.autoDownloadEnabledBool
import com.kofikodr.kofipod.data.repo.notifyNewEpisodesEnabledBool
import com.kofikodr.kofipod.db.Download
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.db.PodcastList
import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.domain.toSummary
import com.kofikodr.kofipod.downloads.DownloadJob
import com.kofikodr.kofipod.downloads.downloadFileName
import com.kofikodr.kofipod.playback.KofipodPlayer
import com.kofikodr.kofipod.playback.PlayableEpisode
import com.kofikodr.kofipod.share.Sharer
import com.mr3y.podcastindex.model.EpisodeFeed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
)

data class ActivePlayback(
    val isPlaying: Boolean = false,
    val progress: Float = 0f,
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
    private val recentlyViewed: RecentlyViewedRepository,
    private val errors: NetworkErrorHandler,
    private val remoteCache: RemoteEpisodeCache,
    // NetworkErrorHandler no longer emits to UiEventBus directly (would
    // couple data → ui). The VM owns the snackbar emission so it can
    // route through the same bus the rest of the screen uses.
    private val uiEvents: com.kofikodr.kofipod.ui.UiEventBus,
) : ViewModel() {
    private val remoteSummary = MutableStateFlow<PodcastSummary?>(null)
    private val remoteEpisodes = MutableStateFlow<List<EpisodePreview>>(emptyList())
    private val loading = MutableStateFlow(false)
    private val loadingMore = MutableStateFlow(false)
    private val displayLimit = MutableStateFlow(PodcastIndexApi.PAGE_SIZE)
    private val remoteLimit = MutableStateFlow(PodcastIndexApi.PAGE_SIZE)
    private val error = MutableStateFlow<String?>(null)
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    /**
     * Currently selected episode for the tablet-landscape master-detail preview pane.
     * VM-local UI state — not persisted, not routed. `null` means "no explicit user
     * selection yet"; the screen defaults to the newest stored episode when rendering.
     * Phone and tablet portraits ignore this field — taps navigate via Route.EpisodeDetail.
     */
    private val _selectedEpisodeId = MutableStateFlow<String?>(null)
    val selectedEpisodeId: StateFlow<String?> = _selectedEpisodeId

    fun selectEpisode(episodeId: String) {
        _selectedEpisodeId.value = episodeId
    }

    private data class StoredBundle(val podcast: Podcast?, val episodes: List<Episode>, val lists: List<PodcastList>)

    private data class RemoteBundle(
        val summary: PodcastSummary?,
        val episodes: List<EpisodePreview>,
        val limit: Int,
        val downloads: List<Download>,
    )

    private data class UiFlags(val loading: Boolean, val loadingMore: Boolean, val displayLimit: Int, val error: String?)

    val state: StateFlow<DetailUiState> =
        combine(
            combine(library.podcastFlow(podcastId), episodes.episodesFlow(podcastId), library.listsFlow(), ::StoredBundle),
            combine(remoteSummary, remoteEpisodes, remoteLimit, downloads.all(), ::RemoteBundle),
            combine(loading, loadingMore, displayLimit, error, ::UiFlags),
        ) { stored, remote, flags ->
            val storedSummary = stored.podcast?.toSummary()
            val merged =
                when {
                    storedSummary != null && remote.summary != null ->
                        storedSummary.copy(
                            category = remote.summary.category.ifBlank { storedSummary.category },
                            episodeCount = if (remote.summary.episodeCount > 0) remote.summary.episodeCount else storedSummary.episodeCount,
                        )
                    storedSummary != null -> storedSummary
                    else -> remote.summary
                }
            val summary =
                if ((merged?.episodeCount ?: 0) == 0 && stored.episodes.isNotEmpty()) {
                    merged?.copy(episodeCount = stored.episodes.size)
                } else {
                    merged
                }
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
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    val playingEpisodeId: StateFlow<String?> =
        player.state
            .map { it.episodeId }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val activePlayback: StateFlow<ActivePlayback> =
        player.state
            .map {
                ActivePlayback(
                    isPlaying = it.isPlaying,
                    progress =
                        if (it.durationMs > 0L) {
                            (it.positionMs.toFloat() / it.durationMs.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        },
                )
            }
            .distinctUntilChanged()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ActivePlayback())

    init {
        loadRemote(loadMore = false)
    }

    fun refresh() {
        if (_refreshing.value) return
        val feedId = podcastId.toLongOrNull()
        if (feedId == null) {
            error.value = "Invalid podcast id"
            return
        }
        viewModelScope.launch {
            _refreshing.value = true
            try {
                val now = Clock.System.now().toEpochMilliseconds()
                if (state.value.inLibrary) {
                    runCatching { episodes.refresh(podcastId, feedId, now) }
                        .onSuccess { result ->
                            if (result.inserted > 0 && state.value.autoDownload) {
                                result.insertedEpisodes.forEach { ep ->
                                    if (ep.enclosureUrl.isNotBlank()) {
                                        downloads.enqueue(
                                            episodeId = ep.id,
                                            url = ep.enclosureUrl,
                                            fileName = downloadFileName(ep.id, ep.enclosureMimeType),
                                            source = DownloadJob.Source.Auto,
                                        )
                                    }
                                }
                                // DB reads + file deletes — keep off the UI dispatcher.
                                // `Dispatchers.IO` is JVM-only; `Default` is multiplatform and
                                // also backed by a worker pool suitable for blocking I/O here.
                                withContext(Dispatchers.Default) { downloads.evictUntilUnderCap() }
                            }
                        }
                        .onFailure {
                            // Refreshing while in-library: stored episodes are already cached, so
                            // route connectivity errors to the global snackbar instead of the
                            // empty-state error field.
                            error.value =
                                errors.handle(
                                    it,
                                    hasCachedData = state.value.storedEpisodes.isNotEmpty(),
                                    fallback = "Failed to refresh episodes",
                                )
                        }
                } else {
                    runCatching {
                        val eps = api.episodesByFeedId(feedId, limit = remoteLimit.value)
                        remoteEpisodes.value = eps.map { it.toPreview() }
                    }.onFailure {
                        error.value =
                            errors.handle(
                                it,
                                hasCachedData = state.value.remoteEpisodes.isNotEmpty(),
                                fallback = "Failed to refresh",
                            )
                    }
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

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
                    val summary = feed.toSummary()
                    remoteSummary.value = summary
                    recentlyViewed.recordView(summary, Clock.System.now().toEpochMilliseconds())
                }
                val eps = api.episodesByFeedId(feedId, limit = remoteLimit.value)
                remoteEpisodes.value = eps.map { it.toPreview() }
                // Seed RemoteEpisodeCache so EpisodeDetailViewModel can render the body
                // for unsubscribed podcasts (the rows never land in the DB).
                remoteSummary.value?.let { summary ->
                    val pod = summary.toTransientPodcast()
                    remoteCache.put(eps.map { it.toCacheEntry(pod) })
                }
            }.onFailure {
                // If user already has the podcast in the library or stored episodes, treat
                // the failure as transient (snackbar) and keep showing cached data. Otherwise
                // surface the friendly empty-state message in the screen.
                val hasCache = state.value.inLibrary || state.value.storedEpisodes.isNotEmpty()
                error.value =
                    errors.handle(
                        it,
                        hasCachedData = hasCache,
                        fallback = "Failed to load podcast",
                        emitSnackbar = { msg ->
                            uiEvents.emit(com.kofikodr.kofipod.ui.UiEvent.Snackbar(msg))
                        },
                    )
            }
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
        if (playingEpisodeId.value == episodeId) {
            if (activePlayback.value.isPlaying) player.pause() else player.resume()
            return
        }
        val summary = current.summary ?: return
        val stored = current.storedEpisodes.firstOrNull { it.id == episodeId }

        data class PlayFields(val title: String, val enclosureUrl: String, val episodeNumber: Int?)
        val fields =
            if (stored != null) {
                PlayFields(stored.title, stored.enclosureUrl, stored.episodeNumber?.toInt())
            } else {
                val remote = current.remoteEpisodes.firstOrNull { it.id == episodeId } ?: return
                PlayFields(remote.title, remote.enclosureUrl, remote.episodeNumber)
            }
        viewModelScope.launch {
            val sourceUrl = downloads.resolvedSourceUrl(episodeId, fields.enclosureUrl) ?: return@launch
            val startMs = playback.positionFor(episodeId)
            player.play(
                PlayableEpisode(
                    episodeId = episodeId,
                    podcastId = podcastId,
                    podcastTitle = summary.title,
                    title = fields.title,
                    artworkUrl = summary.artworkUrl,
                    sourceUrl = sourceUrl,
                    startPositionMs = startMs,
                    episodeNumber = fields.episodeNumber,
                ),
            )
        }
    }

    fun download(episodeId: String) {
        val ep = state.value.storedEpisodes.firstOrNull { it.id == episodeId } ?: return
        if (ep.enclosureUrl.isBlank()) return
        downloads.enqueue(
            episodeId = ep.id,
            url = ep.enclosureUrl,
            fileName = downloadFileName(ep.id, ep.enclosureMimeType),
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
        val title =
            stored?.title
                ?: state.value.remoteEpisodes.firstOrNull { it.id == episodeId }?.title
                ?: return
        val url =
            stored?.enclosureUrl?.takeIf { it.isNotBlank() }
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
                .onFailure {
                    error.value =
                        errors.handle(
                            it,
                            hasCachedData = state.value.storedEpisodes.isNotEmpty(),
                            fallback = "Failed to save episodes",
                        )
                }
        }
    }
}

private fun EpisodeFeed.toPreview(): EpisodePreview =
    EpisodePreview(
        id = id.toString(),
        title = title,
        durationMinutes = (duration ?: 0) / 60,
        enclosureUrl = enclosureUrl,
        episodeNumber = episode,
    )

// Project an API EpisodeFeed into a transient Episode for RemoteEpisodeCache.
// Mirrors EpisodesRepository.refresh()'s insert mapping so cached and persisted
// episodes look identical to downstream consumers — including the Podcast Index
// quirk that `datePublished` deserializes as seconds-as-millis, so we restore
// real milliseconds with `* 1000`.
private fun EpisodeFeed.toCacheEntry(pod: Podcast): RemoteEpisodeCache.Entry =
    RemoteEpisodeCache.Entry(
        episode =
            Episode(
                id = id.toString(),
                podcastId = pod.id,
                guid = guid,
                title = title,
                description = description.orEmpty(),
                publishedAt = datePublished.toEpochMilliseconds() * 1000L,
                durationSec = (duration ?: 0).toLong(),
                enclosureUrl = enclosureUrl,
                enclosureMimeType = enclosureType,
                fileSizeBytes = enclosureLength.toLong(),
                seasonNumber = season?.toLong(),
                episodeNumber = episode?.toLong(),
                imageUrl = image,
                chaptersUrl = chaptersUrl,
                transcriptUrl = transcriptUrl,
            ),
        podcast = pod,
    )

private fun PodcastSummary.toTransientPodcast(): Podcast =
    Podcast(
        id = id,
        title = title,
        author = author,
        description = description,
        artworkUrl = artworkUrl,
        feedUrl = feedUrl,
        listId = null,
        autoDownloadEnabled = 0L,
        notifyNewEpisodesEnabled = 1L,
        lastCheckedAt = null,
        addedAt = 0L,
        primaryCategory = category,
    )

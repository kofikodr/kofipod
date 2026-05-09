// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.bookmarks.BookmarkRepository
import app.kofipod.bookmarks.BookmarkWithContext
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.pkm.PkmExportCoordinator
import app.kofipod.pkm.PkmExportRequest
import app.kofipod.playback.KofipodPlayer
import app.kofipod.playback.PlayableEpisode
import app.kofipod.pro.PaywallRouter
import app.kofipod.pro.ProEntitlement
import app.kofipod.pro.ProEntitlementRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class BookmarkSort { Newest, Oldest }

/** A podcast that has at least one bookmark — appears as a filter chip. */
data class BookmarkPodcastFilter(
    val podcastId: String,
    val title: String,
    val artworkUrl: String,
    val seed: Int,
)

data class BookmarksUiState(
    val rows: List<BookmarkWithContext> = emptyList(),
    val query: String = "",
    /** Pre-filter totals (informational header). */
    val totalSaved: Int = 0,
    val episodeCount: Int = 0,
    /** Per-podcast filter chips, derived from the unfiltered set. */
    val podcastFilters: List<BookmarkPodcastFilter> = emptyList(),
    val selectedPodcastId: String? = null,
    val sort: BookmarkSort = BookmarkSort.Newest,
)

/**
 * Drives the global Bookmarks screen.
 *
 * Reads all bookmarks via the repo's context-aware Flow (which already joins
 * podcast + episode metadata). On top of that:
 * - Per-podcast filter chips are computed from the unfiltered set so toggling
 *   "All" / a specific podcast doesn't make the chip set itself jump.
 * - Search filters across podcast title / episode title / note as a substring
 *   match (lowercased).
 * - Sort flips between Newest and Oldest by `bookmark.timestampMs`.
 *
 * `openAt(...)` resolves the episode through [EpisodeSource] / [DownloadRepository]
 * and starts playback at the bookmark's timestamp — same shape as
 * `PlayerViewModel.step()` so streaming and downloaded paths both work.
 */
class BookmarksViewModel(
    private val bookmarks: BookmarkRepository,
    private val player: KofipodPlayer,
    private val episodes: EpisodeSource,
    private val downloads: DownloadRepository,
    private val pkmExport: PkmExportCoordinator,
    private val paywallRouter: PaywallRouter,
    private val pro: ProEntitlementRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val selectedPodcastId = MutableStateFlow<String?>(null)
    private val sort = MutableStateFlow(BookmarkSort.Newest)

    val state: StateFlow<BookmarksUiState> =
        combine(bookmarks.observeAll(), query, selectedPodcastId, sort) { all, q, pickedPodcast, currentSort ->
            val filters =
                all
                    .distinctBy { it.bookmark.podcastId }
                    .map { row ->
                        BookmarkPodcastFilter(
                            podcastId = row.bookmark.podcastId,
                            title = row.podcastTitle,
                            artworkUrl = row.artworkUrl,
                            seed = row.bookmark.podcastId.hashCode(),
                        )
                    }
                    .sortedBy { it.title.lowercase() }

            // Effective filter — if the user picked a podcast that no longer has
            // bookmarks (e.g. they deleted the last one), fall through to "All"
            // so the screen never reads as silently empty.
            val effectivePodcast =
                pickedPodcast.takeIf { id -> filters.any { it.podcastId == id } }

            val filtered =
                all.asSequence()
                    .filter { effectivePodcast == null || it.bookmark.podcastId == effectivePodcast }
                    .filter { q.isBlank() || it.matches(q) }
                    .toList()
            val sorted =
                when (currentSort) {
                    BookmarkSort.Newest -> filtered.sortedByDescending { it.bookmark.createdAtMs }
                    BookmarkSort.Oldest -> filtered.sortedBy { it.bookmark.createdAtMs }
                }

            BookmarksUiState(
                rows = sorted,
                query = q,
                totalSaved = all.size,
                episodeCount = all.distinctBy { it.bookmark.episodeId }.size,
                podcastFilters = filters,
                selectedPodcastId = effectivePodcast,
                sort = currentSort,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookmarksUiState())

    fun setQuery(q: String) {
        query.value = q
    }

    fun selectPodcast(podcastId: String?) {
        selectedPodcastId.value = podcastId
    }

    fun toggleSort() {
        sort.value =
            when (sort.value) {
                BookmarkSort.Newest -> BookmarkSort.Oldest
                BookmarkSort.Oldest -> BookmarkSort.Newest
            }
    }

    fun delete(id: String) {
        // SQLDelight write — push off the Main dispatcher to avoid jank.
        viewModelScope.launch { bookmarks.deleteById(id) }
    }

    /**
     * Pro-gated. On Pro: open the markdown export sheet for [bookmarkId].
     * On Free / Unknown: open the paywall sheet via [PaywallRouter].
     *
     * Mirrors `PlayerViewModel.onBookmarkTapped` gate semantics.
     */
    fun onExportRequested(bookmarkId: String) {
        when (pro.state.value) {
            is ProEntitlement.Pro -> pkmExport.show(PkmExportRequest.Bookmark(bookmarkId))
            ProEntitlement.Free,
            ProEntitlement.Unknown,
            -> paywallRouter.requestPaywall("paywall_pkm_export_bookmark")
        }
    }

    fun openAt(row: BookmarkWithContext) {
        viewModelScope.launch {
            val ep = episodes.episodeFlow(row.bookmark.episodeId).first() ?: return@launch
            val sourceUrl = downloads.resolvedSourceUrl(ep.id, ep.enclosureUrl) ?: return@launch
            player.play(
                PlayableEpisode(
                    episodeId = ep.id,
                    podcastId = row.bookmark.podcastId,
                    podcastTitle = row.podcastTitle,
                    title = row.episodeTitle,
                    artworkUrl = row.artworkUrl,
                    sourceUrl = sourceUrl,
                    startPositionMs = row.bookmark.timestampMs,
                    episodeNumber = ep.episodeNumber?.toInt(),
                ),
            )
        }
    }

    private fun BookmarkWithContext.matches(q: String): Boolean {
        val needle = q.trim().lowercase()
        return episodeTitle.lowercase().contains(needle) ||
            podcastTitle.lowercase().contains(needle) ||
            (bookmark.note?.lowercase()?.contains(needle) == true)
    }
}

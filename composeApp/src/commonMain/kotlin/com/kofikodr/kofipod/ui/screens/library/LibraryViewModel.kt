// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.data.repo.EpisodeSource
import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.data.repo.StatsRepository
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.Podcast
import com.kofikodr.kofipod.db.PodcastList
import com.kofikodr.kofipod.opml.OpmlAction
import com.kofikodr.kofipod.opml.OpmlController
import com.kofikodr.kofipod.playlists.SmartPlaylist
import com.kofikodr.kofipod.playlists.SmartPlaylistRepository
import com.kofikodr.kofipod.playlists.SmartPlaylistResolver
import com.kofikodr.kofipod.pro.PaywallRouter
import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.pro.ProEntitlementRepository
import com.kofikodr.kofipod.util.slugifyName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class LibraryGroup(val list: PodcastList?, val podcasts: List<Podcast>)

/**
 * One row in the Smart Playlists section of the library grid. Carries both the
 * persisted definition and the live matched count so the tile chrome can render a
 * meaningful subtitle without re-evaluating the predicate inside the composable.
 */
data class SmartPlaylistTileData(
    val playlist: SmartPlaylist,
    val matchedCount: Int,
)

data class LibraryUiState(
    val groups: List<LibraryGroup> = emptyList(),
    // Folder listId (or null for Unfiled) → true when any podcast in that bucket has a new episode.
    val groupsWithNew: Set<String?> = emptySet(),
    val statsHasUnseenTierChange: Boolean = false,
    val smartPlaylists: List<SmartPlaylistTileData> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    private val repo: LibraryRepository,
    private val episodes: EpisodeSource,
    stats: StatsRepository,
    private val opml: OpmlController,
    private val pro: ProEntitlementRepository,
    private val paywallRouter: PaywallRouter,
    private val playlists: SmartPlaylistRepository,
    private val resolver: SmartPlaylistResolver,
) : ViewModel() {
    val opmlAction: StateFlow<OpmlAction> = opml.action

    /**
     * Per-playlist live tile data — for each persisted playlist, the resolver streams
     * the matched-fact list and we project to its size. Empty-list short-circuit keeps
     * us from instantiating a `combine(emptyList)` (which throws); when the user has no
     * playlists yet, the flow simply emits an empty list.
     */
    private val smartPlaylistTilesFlow: Flow<List<SmartPlaylistTileData>> =
        playlists.observeAll().flatMapLatest { all ->
            if (all.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(
                    all.map { p ->
                        resolver.observe(p.predicate).map { matched -> SmartPlaylistTileData(p, matched.size) }
                    },
                ) { it.toList() }
            }
        }

    val state: StateFlow<LibraryUiState> =
        combine(
            repo.listsFlow(),
            repo.podcastsFlow(),
            episodes.newEpisodeCountsFlow(),
            stats.hasUnseenTierChange(),
            smartPlaylistTilesFlow,
        ) { lists, podcasts, newCounts, statsBadge, plTiles ->
            val byList = podcasts.groupBy { it.listId }
            val named = lists.map { l -> LibraryGroup(l, byList[l.id].orEmpty()) }
            val unfiled = byList[null].orEmpty()
            val groups = if (unfiled.isEmpty()) named else named + LibraryGroup(null, unfiled)

            val groupsWithNew: Set<String?> =
                groups
                    .filter { g -> g.podcasts.any { (newCounts[it.id] ?: 0) > 0 } }
                    .map { it.list?.id }
                    .toSet()

            LibraryUiState(
                groups = groups,
                groupsWithNew = groupsWithNew,
                statsHasUnseenTierChange = statsBadge,
                smartPlaylists = plTiles,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /**
     * Currently selected subscription for the tablet-landscape master-detail preview pane.
     * VM-local UI state — not persisted across process death, not routed (the URL only
     * changes when the user explicitly opens the podcast via the detail pane's "Open"
     * CTA). `null` means "show the empty-detail hint."
     */
    private val _selectedPodcastId = MutableStateFlow<String?>(null)
    val selectedPodcastId: StateFlow<String?> = _selectedPodcastId.asStateFlow()

    /**
     * Last [PREVIEW_EPISODE_LIMIT] episodes for the selected podcast, sourced from the
     * existing `EpisodeSource.episodesFlow`. `flatMapLatest` cancels the previous
     * episodesFlow subscription when the selection changes so we never accumulate
     * orphaned collectors as the user clicks through the master grid.
     */
    val selectedEpisodes: StateFlow<List<Episode>> =
        selectedPodcastId
            .flatMapLatest { id ->
                if (id == null) {
                    flowOf(emptyList())
                } else {
                    episodes.episodesFlow(id).map { it.take(PREVIEW_EPISODE_LIMIT) }
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectPodcast(podcastId: String?) {
        _selectedPodcastId.value = podcastId
    }

    fun createList(name: String) {
        if (name.isBlank()) return
        val existing = state.value.groups.mapNotNull { it.list?.id }.toSet()
        val id = slugifyName(name, existing)
        val position = state.value.groups.count { it.list != null }
        repo.createList(id, name.trim(), position, Clock.System.now().toEpochMilliseconds())
    }

    fun deletePodcast(podcastId: String) = repo.deletePodcast(podcastId)

    fun deleteList(listId: String) = repo.deleteList(listId)

    fun importOpml() = opml.importOpml()

    /**
     * Returns true when the caller should navigate to the Bookmarks screen.
     * Returns false (and opens the paywall) when the user is Free or Unknown.
     * Mirrors `PlayerViewModel.onBookmarkTapped` gate semantics.
     */
    fun onBookmarksTapped(): Boolean = gate("paywall_bookmark")

    /**
     * Returns true when the caller should navigate to the Library search screen.
     * Returns false (and opens the paywall) when the user is Free or Unknown.
     * Same gate semantics as [onBookmarksTapped]. Distinct trigger key so future
     * conversion analytics can attribute paywall opens by surface.
     */
    fun onLibrarySearchTapped(): Boolean = gate("paywall_library_search")

    /**
     * Returns true when the caller should navigate to the Smart Playlist detail screen.
     * Returns false (and opens the paywall) when the user is Free or Unknown.
     */
    fun onSmartPlaylistTapped(): Boolean = gate("paywall_smart_playlists")

    /**
     * Returns true when the caller should navigate to the Smart Playlist editor in
     * create-mode. Same trigger key as [onSmartPlaylistTapped] because the paywall is
     * the same offering — no separate attribution needed.
     */
    fun onCreateSmartPlaylistTapped(): Boolean = gate("paywall_smart_playlists")

    /**
     * Deletes a playlist row by id. Cancellation propagates so structured concurrency
     * stays intact when the user navigates away mid-delete; other failures are
     * swallowed (the row simply remains and the next observation reflects it).
     */
    fun deleteSmartPlaylist(id: String) {
        viewModelScope.launch {
            runCatching { playlists.delete(id) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }

    companion object {
        /**
         * How many recent episodes the tablet-landscape preview pane surfaces for the
         * selected podcast. Matches plan §2.3's "last N episodes" guideline; kept here
         * as a single source of truth for the VM-derived flow.
         */
        const val PREVIEW_EPISODE_LIMIT: Int = 5
    }

    private fun gate(triggerKey: String): Boolean =
        when (pro.state.value) {
            is ProEntitlement.Pro -> true
            ProEntitlement.Free,
            ProEntitlement.Unknown,
            -> {
                paywallRouter.requestPaywall(triggerKey)
                false
            }
        }
}

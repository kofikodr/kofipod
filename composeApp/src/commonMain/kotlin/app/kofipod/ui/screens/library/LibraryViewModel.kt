// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.data.repo.StatsRepository
import app.kofipod.db.Podcast
import app.kofipod.db.PodcastList
import app.kofipod.opml.OpmlAction
import app.kofipod.opml.OpmlController
import app.kofipod.util.slugifyName
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Clock

data class LibraryGroup(val list: PodcastList?, val podcasts: List<Podcast>)

data class LibraryUiState(
    val groups: List<LibraryGroup> = emptyList(),
    // Folder listId (or null for Unfiled) → true when any podcast in that bucket has a new episode.
    val groupsWithNew: Set<String?> = emptySet(),
    val statsHasUnseenTierChange: Boolean = false,
)

class LibraryViewModel(
    private val repo: LibraryRepository,
    episodes: EpisodeSource,
    stats: StatsRepository,
    private val opml: OpmlController,
) : ViewModel() {
    val opmlAction: StateFlow<OpmlAction> = opml.action

    val state: StateFlow<LibraryUiState> =
        combine(
            repo.listsFlow(),
            repo.podcastsFlow(),
            episodes.newEpisodeCountsFlow(),
            stats.hasUnseenTierChange(),
        ) { lists, podcasts, newCounts, statsBadge ->
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
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

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
}

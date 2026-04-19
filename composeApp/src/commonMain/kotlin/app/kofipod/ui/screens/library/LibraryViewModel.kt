// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.db.Podcast
import app.kofipod.db.PodcastList
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.datetime.Clock

data class LibraryGroup(val list: PodcastList?, val podcasts: List<Podcast>)

data class LibraryUiState(
    val groups: List<LibraryGroup> = emptyList(),
)

class LibraryViewModel(
    private val repo: LibraryRepository,
) : ViewModel() {
    val state: StateFlow<LibraryUiState> =
        combine(
            repo.listsFlow(),
            repo.podcastsFlow(),
        ) { lists, podcasts ->
            val byList = podcasts.groupBy { it.listId }
            val named = lists.map { l -> LibraryGroup(l, byList[l.id].orEmpty()) }
            val unfiled = byList[null].orEmpty()
            val groups = if (unfiled.isEmpty()) named else named + LibraryGroup(null, unfiled)
            LibraryUiState(groups = groups)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    fun createList(name: String) {
        if (name.isBlank()) return
        val id =
            name.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .ifBlank { "list-${Clock.System.now().toEpochMilliseconds()}" }
        val position = state.value.groups.count { it.list != null }
        repo.createList(id, name.trim(), position, Clock.System.now().toEpochMilliseconds())
    }

    fun deletePodcast(podcastId: String) = repo.deletePodcast(podcastId)

    fun deleteList(listId: String) = repo.deleteList(listId)
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.db.Podcast
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class LibraryDetailUiState(
    val listId: String? = null,
    val listName: String = "",
    val podcasts: List<Podcast> = emptyList(),
    val gone: Boolean = false,
)

class LibraryDetailViewModel(
    private val listId: String?,
    private val repo: LibraryRepository,
) : ViewModel() {

    val state: StateFlow<LibraryDetailUiState> = combine(
        repo.listsFlow(),
        repo.podcastsInList(listId),
    ) { lists, podcasts ->
        val resolved = listId?.let { id -> lists.firstOrNull { it.id == id } }
        // listId set but the list row has been deleted → detail is gone
        val gone = listId != null && resolved == null
        LibraryDetailUiState(
            listId = listId,
            listName = resolved?.name ?: if (listId == null) "Unfiled" else "",
            podcasts = podcasts,
            gone = gone,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryDetailUiState(listId = listId))

    fun deletePodcast(podcastId: String) = repo.deletePodcast(podcastId)

    fun deleteList() {
        val id = listId ?: return
        repo.deleteList(id)
    }
}

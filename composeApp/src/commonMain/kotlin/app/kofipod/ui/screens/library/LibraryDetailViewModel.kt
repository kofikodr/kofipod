// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.LibraryRepository
import app.kofipod.data.repo.RecentlyViewedRepository
import app.kofipod.data.repo.SearchSource
import app.kofipod.db.Podcast
import app.kofipod.domain.PodcastSummary
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

data class LibraryDetailUiState(
    val listId: String? = null,
    val listName: String = "",
    val podcasts: List<Podcast> = emptyList(),
    val gone: Boolean = false,
    val searchQuery: String = "",
    val searchResults: List<PodcastSummary> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,
    val recentlyViewed: List<PodcastSummary> = emptyList(),
)

class LibraryDetailViewModel(
    private val listId: String?,
    private val repo: LibraryRepository,
    private val search: SearchSource,
    private val recentlyViewed: RecentlyViewedRepository,
) : ViewModel() {
    private val searchQuery = MutableStateFlow("")
    private val searchResults = MutableStateFlow<List<PodcastSummary>>(emptyList())
    private val searching = MutableStateFlow(false)
    private val searchError = MutableStateFlow<String?>(null)

    private var searchJob: Job? = null

    val state: StateFlow<LibraryDetailUiState> =
        combine(
            combine(repo.listsFlow(), repo.podcastsInList(listId), ::Pair),
            combine(searchQuery, searchResults, searching, searchError, ::SearchBundle),
            recentlyViewed.recentExcludingSavedFlow(),
        ) { (lists, podcasts), s, recent ->
            val resolved = listId?.let { id -> lists.firstOrNull { it.id == id } }
            val gone = listId != null && resolved == null
            LibraryDetailUiState(
                listId = listId,
                listName = resolved?.name ?: if (listId == null) "Unfiled" else "",
                podcasts = podcasts,
                gone = gone,
                searchQuery = s.query,
                searchResults = s.results,
                searching = s.loading,
                searchError = s.error,
                recentlyViewed = recent,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryDetailUiState(listId = listId))

    fun deletePodcast(podcastId: String) = repo.deletePodcast(podcastId)

    fun deleteList() {
        val id = listId ?: return
        repo.deleteList(id)
    }

    fun renameList(name: String) {
        val id = listId ?: return
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        repo.renameList(id, trimmed)
    }

    fun setSearchQuery(query: String) {
        searchQuery.value = query
        scheduleSearch()
    }

    fun addSummaryToList(summary: PodcastSummary) {
        val now = Clock.System.now().toEpochMilliseconds()
        repo.savePodcast(summary, listId, now)
        recentlyViewed.forget(summary.id)
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        val query = searchQuery.value
        if (query.isBlank()) {
            searchResults.value = emptyList()
            searching.value = false
            searchError.value = null
            return
        }
        searchJob =
            viewModelScope.launch {
                delay(DEBOUNCE_MS)
                searching.value = true
                searchError.value = null
                runCatching { search.searchAll(query) }
                    .onSuccess {
                        searchResults.value = it
                        searching.value = false
                    }
                    .onFailure {
                        searchError.value = it.message ?: "Search failed"
                        searching.value = false
                    }
            }
    }

    private data class SearchBundle(
        val query: String,
        val results: List<PodcastSummary>,
        val loading: Boolean,
        val error: String?,
    )

    companion object {
        const val DEBOUNCE_MS: Long = 350
    }
}

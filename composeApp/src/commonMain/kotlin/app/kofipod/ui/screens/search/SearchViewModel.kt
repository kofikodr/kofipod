// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.api.PodcastIndexApi
import app.kofipod.data.repo.CategoriesSource
import app.kofipod.data.repo.SearchSource
import app.kofipod.domain.PodcastSummary
import com.mr3y.podcastindex.model.Category
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class SearchTab { All, Title, Person }

data class SearchUiState(
    val query: String = "",
    val tab: SearchTab = SearchTab.All,
    val results: List<PodcastSummary> = emptyList(),
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val error: String? = null,
    val popularCategories: List<Category> = emptyList(),
)

class SearchViewModel(
    private val repo: SearchSource,
    categories: CategoriesSource,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState(popularCategories = categories.popular()))
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var currentLimit: Int = PodcastIndexApi.PAGE_SIZE

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        currentLimit = PodcastIndexApi.PAGE_SIZE
        scheduleSearch(loadMore = false)
    }

    fun setTab(tab: SearchTab) {
        _state.value = _state.value.copy(tab = tab)
        currentLimit = PodcastIndexApi.PAGE_SIZE
        scheduleSearch(loadMore = false)
    }

    fun loadMore() {
        val s = _state.value
        if (s.loading || s.loadingMore || !s.hasMore || s.query.isBlank()) return
        currentLimit += PodcastIndexApi.PAGE_SIZE
        scheduleSearch(loadMore = true)
    }

    private fun scheduleSearch(loadMore: Boolean) {
        searchJob?.cancel()
        val s = _state.value
        if (s.query.isBlank()) {
            _state.value = s.copy(results = emptyList(), loading = false, loadingMore = false, hasMore = false, error = null)
            return
        }
        searchJob =
            viewModelScope.launch {
                if (!loadMore) delay(DEBOUNCE_MS)
                _state.value =
                    _state.value.copy(
                        loading = !loadMore,
                        loadingMore = loadMore,
                        error = null,
                    )
                val limit = currentLimit
                runCatching {
                    when (s.tab) {
                        SearchTab.All -> repo.searchAll(s.query, limit)
                        SearchTab.Title -> repo.searchByTitle(s.query, limit)
                        SearchTab.Person -> repo.searchByPerson(s.query, limit)
                    }
                }.onSuccess { results ->
                    _state.value =
                        _state.value.copy(
                            results = results,
                            loading = false,
                            loadingMore = false,
                            hasMore = results.size >= limit,
                        )
                }.onFailure { e ->
                    _state.value =
                        _state.value.copy(
                            loading = false,
                            loadingMore = false,
                            error = e.message ?: "Search failed",
                        )
                }
            }
    }

    companion object {
        const val DEBOUNCE_MS: Long = 350
    }
}

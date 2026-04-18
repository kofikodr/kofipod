// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.SearchSource
import app.kofipod.domain.PodcastSummary
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
    val error: String? = null,
)

class SearchViewModel(private val repo: SearchSource) : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        scheduleSearch()
    }

    fun setTab(tab: SearchTab) {
        _state.value = _state.value.copy(tab = tab)
        scheduleSearch()
    }

    private fun scheduleSearch() {
        searchJob?.cancel()
        val s = _state.value
        if (s.query.isBlank()) {
            _state.value = s.copy(results = emptyList(), loading = false, error = null)
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            _state.value = _state.value.copy(loading = true, error = null)
            runCatching {
                when (s.tab) {
                    SearchTab.All -> repo.searchAll(s.query)
                    SearchTab.Title -> repo.searchByTitle(s.query)
                    SearchTab.Person -> repo.searchByPerson(s.query)
                }
            }.onSuccess { results ->
                _state.value = _state.value.copy(results = results, loading = false)
            }.onFailure { e ->
                _state.value = _state.value.copy(loading = false, error = e.message ?: "Search failed")
            }
        }
    }

    companion object {
        const val DEBOUNCE_MS: Long = 350
    }
}

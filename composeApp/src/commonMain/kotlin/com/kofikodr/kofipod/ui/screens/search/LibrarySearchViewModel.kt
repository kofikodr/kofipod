// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.search.LibrarySearchKind
import com.kofikodr.kofipod.search.LibrarySearchRepository
import com.kofikodr.kofipod.search.LibrarySearchResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

data class LibrarySearchUiState(
    val query: String = "",
    val activeKind: LibrarySearchKind? = null,
    val results: List<LibrarySearchResult> = emptyList(),
)

class LibrarySearchViewModel(
    private val repo: LibrarySearchRepository,
) : ViewModel() {
    private val rawQuery = MutableStateFlow("")
    private val activeKind = MutableStateFlow<LibrarySearchKind?>(null)

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val state: StateFlow<LibrarySearchUiState> =
        run {
            val results: Flow<List<LibrarySearchResult>> =
                combine(
                    rawQuery.debounce(QUERY_DEBOUNCE_MS).distinctUntilChanged(),
                    activeKind,
                ) { q, k -> q to k }
                    .flatMapLatest { (q, k) -> repo.search(q, k) }

            combine(rawQuery, activeKind, results) { q, k, r ->
                LibrarySearchUiState(query = q, activeKind = k, results = r)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibrarySearchUiState())
        }

    fun onQueryChanged(value: String) {
        rawQuery.value = value
    }

    fun onKindChipTapped(kind: LibrarySearchKind?) {
        activeKind.value = kind
    }

    private companion object {
        const val QUERY_DEBOUNCE_MS = 200L
    }
}

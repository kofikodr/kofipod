// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.DiscoverySource
import app.kofipod.domain.PodcastSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class StarterPackUiState(
    val loading: Boolean = true,
    val picks: List<PodcastSummary> = emptyList(),
    val error: String? = null,
)

class StarterPackViewModel(private val discovery: DiscoverySource) : ViewModel() {
    private val _state = MutableStateFlow(StarterPackUiState())
    val state: StateFlow<StarterPackUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            runCatching { discovery.trending(limit = PACK_SIZE) }
                .onSuccess { picks ->
                    _state.value = StarterPackUiState(loading = false, picks = picks)
                }
                .onFailure { e ->
                    _state.value = _state.value.copy(loading = false, error = e.message ?: "Couldn't load trending podcasts")
                }
        }
    }

    companion object {
        const val PACK_SIZE = 12
    }
}

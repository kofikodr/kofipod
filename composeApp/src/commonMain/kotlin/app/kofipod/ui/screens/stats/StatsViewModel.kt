// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.data.repo.StatsRepository
import app.kofipod.data.repo.StatsSnapshot
import app.kofipod.domain.Tier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class StatsUiState(
    val snapshot: StatsSnapshot? = null,
    val hasAnyData: Boolean = false,
)

class StatsViewModel(
    private val repo: StatsRepository,
) : ViewModel() {
    val state: StateFlow<StatsUiState> =
        repo.snapshot()
            .onEach { snap ->
                // Persist the newly-emitted tier (if any) so subsequent re-computes can
                // apply hysteresis against it. The repository skips the write when the
                // tier hasn't changed, so this is cheap even on every emission.
                snap.tier?.let { repo.persistEmittedTier(it) }
            }
            .map { StatsUiState(snapshot = it, hasAnyData = it.totalSecondsAllTime > 0L) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    private val tierExplainOpenState = MutableStateFlow(false)
    val tierExplainOpen: StateFlow<Boolean> = tierExplainOpenState.asStateFlow()

    fun openTierExplain() {
        tierExplainOpenState.value = true
    }

    fun closeTierExplain() {
        tierExplainOpenState.value = false
    }

    /**
     * Called when the screen is first shown — clears the unseen-tier dot for the user.
     * If no tier has been emitted yet, do nothing: the user can't have "missed" a change
     * that hasn't happened, and a future first emission still gets a dot because the
     * seen-marker is absent.
     */
    fun markTierSeen() {
        viewModelScope.launch {
            repo.emittedTierNow()?.let { repo.markTierSeen(it) }
        }
    }

    fun currentTier(): Tier? = state.value.snapshot?.tier
}

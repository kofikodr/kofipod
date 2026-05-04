// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.ai.DiscussRepository
import app.kofipod.ai.DiscussUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Owns one Discuss tab card's state for one episode. Thin wrapper over
 * [DiscussRepository] — the repository is the single source of truth and
 * survives navigation, so this VM has no in-memory state to defend.
 *
 * The full-screen Ask Gemini surface uses its own [app.kofipod.ui.screens.askgemini.AskGeminiViewModel]
 * because it carries composer state + citation seeking that the tab card
 * doesn't need. Both VMs project from the same repository state, so a turn
 * landing in the DB updates both screens in lockstep.
 */
class DiscussViewModel(
    private val episodeId: String,
    private val repo: DiscussRepository,
) : ViewModel() {
    val state: StateFlow<DiscussUiState> =
        repo.observeFor(episodeId).stateIn(
            scope = viewModelScope,
            // Match the convention used by AiSummaryViewModel — give the flow a
            // brief grace period so a tab-switch round-trip doesn't drop the
            // cached upstream state.
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = DiscussUiState.Hidden,
        )

    fun clearChat() =
        viewModelScope.launch {
            repo.clearForEpisode(episodeId)
        }
}

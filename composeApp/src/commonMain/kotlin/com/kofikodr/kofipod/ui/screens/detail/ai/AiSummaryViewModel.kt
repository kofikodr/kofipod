// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kofikodr.kofipod.ai.AiSummaryRepository
import com.kofikodr.kofipod.ai.AiSummaryUiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Owns one Summary tab's state for one episode. Thin wrapper over
 * [AiSummaryRepository] — the repository is the single source of truth and
 * survives navigation, so this VM has no in-memory state to defend.
 */
class AiSummaryViewModel(
    private val episodeId: String,
    private val repo: AiSummaryRepository,
) : ViewModel() {
    val state: StateFlow<AiSummaryUiState> =
        repo.observeFor(episodeId).stateIn(
            scope = viewModelScope,
            // Match the convention used by EpisodeDetailViewModel — give the flow a
            // brief grace period so a tab-switch round-trip doesn't drop the cached
            // upstream state.
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = AiSummaryUiState.Hidden,
        )

    fun onGenerate() {
        repo.generate(episodeId)
    }

    fun onCancel() {
        repo.cancel(episodeId)
    }
}

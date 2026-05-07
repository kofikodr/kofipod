// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.playlists.SmartPlaylistRepository
import app.kofipod.playlists.SmartPlaylistResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the matched-episode detail screen for a single Smart Playlist.
 *
 * Observes [SmartPlaylistRepository.observe] for the playlist row and, while the row
 * exists, pipes its predicate into [SmartPlaylistResolver.observe] so the matched-fact
 * list re-emits whenever either the predicate or the underlying facts change. When the
 * row is deleted (observed `null`) the resolver subscription is short-circuited to an
 * empty flow and `notFound` flips to `true` — see [SmartPlaylistDetailUiState]'s kdoc
 * for why we distinguish "loading" from "not found".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartPlaylistDetailViewModel(
    private val playlists: SmartPlaylistRepository,
    private val resolver: SmartPlaylistResolver,
    private val playlistId: String,
) : ViewModel() {
    val state: StateFlow<SmartPlaylistDetailUiState> =
        playlists
            .observe(playlistId)
            .flatMapLatest { playlist ->
                if (playlist == null) {
                    flowOf(
                        SmartPlaylistDetailUiState(
                            playlist = null,
                            matched = emptyList(),
                            notFound = true,
                        ),
                    )
                } else {
                    combine(
                        flowOf(playlist),
                        resolver.observe(playlist.predicate),
                    ) { p, matched ->
                        SmartPlaylistDetailUiState(
                            playlist = p,
                            matched = matched,
                            notFound = false,
                        )
                    }
                }
            }
            .stateIn(
                // Eager subscription matches the editor-VM convention: the detail
                // screen is short-lived modal-style chrome, the matched list must be
                // live the moment the user opens it (no first-frame "0 episodes"
                // flicker), and tests can read `state.value` synchronously after
                // `advanceUntilIdle` without first wiring a collector. WhileSubscribed
                // would defer upstream connection until first collector and would
                // leave `state.value` at the initial value during unit tests.
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = SmartPlaylistDetailUiState(),
            )

    /**
     * Deletes the playlist row. Cancellation propagates so structured concurrency
     * stays intact when the user navigates away mid-delete; any other failure is
     * swallowed (the row simply remains and the next observation will reflect it).
     */
    fun delete() {
        viewModelScope.launch {
            runCatching { playlists.delete(playlistId) }
                .onFailure { if (it is CancellationException) throw it }
        }
    }
}

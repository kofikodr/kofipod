// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.playlists

import app.kofipod.playlists.SmartPlaylistPredicate

/** Lightweight projection of a Podcast row used by the editor's per-podcast chip row. */
data class PodcastChoice(val id: String, val title: String)

/**
 * View-state for the Smart Playlist editor screen (Slice 7 Task 9).
 *
 * Holds the user's in-progress draft (`name` + `predicate`), the live "matched" preview
 * derived from the resolver, and the available-podcasts list used to render the per-podcast
 * filter chips. `isEditMode` is `true` when the editor was opened against an existing
 * playlist id (vs. create mode); the screen uses it to surface the Delete button.
 */
data class SmartPlaylistEditorUiState(
    val name: String = "",
    val predicate: SmartPlaylistPredicate = SmartPlaylistPredicate.EMPTY,
    val matchedCount: Int = 0,
    val matchedPreview: List<String> = emptyList(),
    val availablePodcasts: List<PodcastChoice> = emptyList(),
    val isEditMode: Boolean = false,
    val isSaving: Boolean = false,
    val saveError: String? = null,
)

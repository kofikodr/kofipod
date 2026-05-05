// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.bookmarks

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed class BookmarkComposerState {
    data object Hidden : BookmarkComposerState()

    data class Visible(
        val episodeId: String,
        val podcastId: String,
        val episodeTitle: String,
        val podcastTitle: String,
        val timestampMs: Long,
    ) : BookmarkComposerState()
}

/**
 * Process-wide bus between "user tapped Bookmark on the Player" and the
 * AppShell-hosted `BookmarkComposerSheet`. Hoisting at the shell rather
 * than inside the Player screen lets the sheet survive navigation (e.g.
 * the user pulls it open and then taps the back button on the player —
 * the sheet stays up until they Save or Cancel).
 *
 * Single-instance via Koin. State is intentionally last-write-wins:
 * tapping bookmark again while a previous quick-add is still open
 * replaces the in-flight snapshot. That matches user intent ("oops,
 * actually grab THIS moment instead").
 *
 * Save semantics: this class is pure UI state — it never writes to the
 * database. Saving is the sheet's responsibility: `BookmarkComposerSheet`
 * reads the current snapshot, calls `BookmarkRepository.add(...)`, then
 * calls [cancel] to dismiss. Keeping the repository out of this class lets
 * tests verify the seam without touching SQLDelight.
 */
class BookmarkComposer {
    private val _state = MutableStateFlow<BookmarkComposerState>(BookmarkComposerState.Hidden)
    val state: StateFlow<BookmarkComposerState> = _state.asStateFlow()

    fun requestQuickAdd(
        episodeId: String,
        podcastId: String,
        episodeTitle: String,
        podcastTitle: String,
        timestampMs: Long,
    ) {
        _state.value =
            BookmarkComposerState.Visible(
                episodeId = episodeId,
                podcastId = podcastId,
                episodeTitle = episodeTitle,
                podcastTitle = podcastTitle,
                timestampMs = timestampMs,
            )
    }

    fun cancel() {
        _state.value = BookmarkComposerState.Hidden
    }
}

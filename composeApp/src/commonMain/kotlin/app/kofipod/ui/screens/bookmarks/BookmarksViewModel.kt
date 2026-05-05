// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.bookmarks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kofipod.bookmarks.BookmarkRepository
import app.kofipod.bookmarks.BookmarkWithContext
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.playback.KofipodPlayer
import app.kofipod.playback.PlayableEpisode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class BookmarksUiState(
    val rows: List<BookmarkWithContext> = emptyList(),
    val query: String = "",
)

/**
 * Drives the global Bookmarks screen. Reads all bookmarks via the repo's
 * context-aware Flow (joins podcast + episode metadata), with an in-memory
 * substring filter over podcast title / episode title / note.
 *
 * `openAt(...)` resolves the episode through [EpisodeSource] / [DownloadRepository]
 * and starts playback at the bookmark's timestamp — same shape as
 * `PlayerViewModel.step()` so streaming and downloaded paths both work.
 */
class BookmarksViewModel(
    private val bookmarks: BookmarkRepository,
    private val player: KofipodPlayer,
    private val episodes: EpisodeSource,
    private val downloads: DownloadRepository,
) : ViewModel() {
    private val query = MutableStateFlow("")

    val state: StateFlow<BookmarksUiState> =
        combine(bookmarks.observeAll(), query) { rows, q ->
            BookmarksUiState(
                rows = if (q.isBlank()) rows else rows.filter { it.matches(q) },
                query = q,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BookmarksUiState())

    fun setQuery(q: String) {
        query.value = q
    }

    fun delete(id: String) = bookmarks.deleteById(id)

    fun openAt(row: BookmarkWithContext) {
        viewModelScope.launch {
            val ep = episodes.episodeFlow(row.bookmark.episodeId).first() ?: return@launch
            val sourceUrl = downloads.resolvedSourceUrl(ep.id, ep.enclosureUrl) ?: return@launch
            player.play(
                PlayableEpisode(
                    episodeId = ep.id,
                    podcastId = row.bookmark.podcastId,
                    podcastTitle = row.podcastTitle,
                    title = row.episodeTitle,
                    artworkUrl = row.artworkUrl,
                    sourceUrl = sourceUrl,
                    startPositionMs = row.bookmark.timestampMs,
                    episodeNumber = ep.episodeNumber?.toInt(),
                ),
            )
        }
    }

    private fun BookmarkWithContext.matches(q: String): Boolean {
        val needle = q.trim().lowercase()
        return episodeTitle.lowercase().contains(needle) ||
            podcastTitle.lowercase().contains(needle) ||
            (bookmark.note?.lowercase()?.contains(needle) == true)
    }
}

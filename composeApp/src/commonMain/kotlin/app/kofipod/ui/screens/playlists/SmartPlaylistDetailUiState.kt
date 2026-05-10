// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.playlists

import app.kofipod.playlists.EpisodeFacts
import app.kofipod.playlists.SmartPlaylist

/**
 * UI state for [SmartPlaylistDetailScreen].
 *
 * `playlist` is `null` until the repository emits the first observation. After that,
 * a subsequent `null` emission (e.g. when the playlist row is deleted while the screen
 * is open) flips [notFound] to `true` so the screen can render the "Playlist deleted"
 * empty state and pop back. We deliberately don't conflate "not yet loaded" with
 * "not found" because the screen would otherwise flash the not-found state on open.
 */
data class SmartPlaylistDetailUiState(
    val playlist: SmartPlaylist? = null,
    val matched: List<EpisodeFacts> = emptyList(),
    val notFound: Boolean = false,
)

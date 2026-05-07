// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

import kotlinx.coroutines.flow.Flow

/**
 * Persistence seam for user-defined Smart Playlists.
 *
 * Storage encodes the [SmartPlaylistPredicate] as JSON in the `SmartPlaylist.predicateJson`
 * column; observers decode lazily and fall back to [SmartPlaylistPredicate.EMPTY] on parse
 * failure so a future-schema row never tears down the UI.
 */
interface SmartPlaylistRepository {
    /** All playlists ordered by creation time (oldest first). */
    fun observeAll(): Flow<List<SmartPlaylist>>

    /** Single playlist by id, or null if not found. */
    fun observe(id: String): Flow<SmartPlaylist?>

    /** Insert-or-replace by primary key (`id`). */
    suspend fun save(playlist: SmartPlaylist)

    /** Delete by id; no-op if the row doesn't exist. */
    suspend fun delete(id: String)
}

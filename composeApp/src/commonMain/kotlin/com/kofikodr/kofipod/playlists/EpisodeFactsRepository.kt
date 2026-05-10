// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playlists

import kotlinx.coroutines.flow.Flow

/**
 * Source of [EpisodeFacts] for the Smart Playlist resolver.
 *
 * Implementations join across the relevant tables (Episode + Snippet + Download +
 * PlaybackState) and observe changes so the resolver can emit a fresh matched
 * episode list whenever the underlying data shifts. The interface stays tiny on
 * purpose — Task 5 wires the SQLDelight-backed implementation; Task 3 only ships
 * the seam so downstream tasks (resolver, ViewModels) can compile against it.
 */
interface EpisodeFactsRepository {
    /**
     * Observes facts for every episode currently in the database.
     *
     * Emits a complete snapshot on every change. Order is implementation-defined;
     * callers that need a deterministic order must sort.
     */
    fun observeAll(): Flow<List<EpisodeFacts>>
}

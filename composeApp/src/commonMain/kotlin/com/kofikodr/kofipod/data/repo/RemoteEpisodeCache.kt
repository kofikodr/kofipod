// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.repo

import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.db.Podcast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Process-lifetime cache of remote-only episode projections, keyed by episodeId.
 *
 * Populated by `PodcastDetailViewModel` when it loads an unsubscribed podcast
 * from the Podcast Index API (episodes never land in the SQLDelight DB until the
 * user subscribes). Read by `EpisodeDetailViewModel` as a fallback when its DB
 * lookup returns null, so navigating to a remote-only episode shows the body
 * instead of an indefinite loading spinner / empty screen.
 *
 * No TTL — entries simply stay until the process dies. Cache size is bounded by
 * the number of unsubscribed podcasts the user browses in a session, which is
 * tiny compared to the data the VMs already hold in memory.
 */
class RemoteEpisodeCache {
    data class Entry(val episode: Episode, val podcast: Podcast)

    private val state = MutableStateFlow<Map<String, Entry>>(emptyMap())
    val entries: StateFlow<Map<String, Entry>> = state.asStateFlow()

    fun put(entries: List<Entry>) {
        if (entries.isEmpty()) return
        // `update` performs an atomic compare-and-set loop, so concurrent putters from
        // different VMs/coroutines can't lose entries via read-modify-write races.
        state.update { it + entries.associateBy { e -> e.episode.id } }
    }

    fun get(episodeId: String): Entry? = state.value[episodeId]

    fun observe(episodeId: String): Flow<Entry?> = state.map { it[episodeId] }
}

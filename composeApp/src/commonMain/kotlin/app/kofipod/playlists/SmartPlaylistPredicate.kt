// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

import kotlinx.serialization.Serializable

@Serializable
enum class PlayState { Unplayed, InProgress, Completed }

@Serializable
data class DurationRange(
    val minSec: Int? = null,
    val maxSec: Int? = null,
)

@Serializable
data class SmartPlaylistPredicate(
    val state: PlayState? = null,
    val durationRange: DurationRange? = null,
    val podcastIds: Set<String>? = null,
    val maxAgeDays: Int? = null,
    val hasTranscript: Boolean? = null,
    val downloadedOnly: Boolean? = null,
    val hasSnippets: Boolean? = null,
) {
    companion object {
        val EMPTY = SmartPlaylistPredicate()
    }
}

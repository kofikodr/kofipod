// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

/**
 * Read-only projection of the per-episode signals consumed by the Smart Playlist
 * predicate resolver (Slice 7 Task 7).
 *
 * Aggregates fields drawn from `Episode`, `Snippet`, `Download`, `PlaybackState`,
 * and the on-disk transcript cache so the resolver can evaluate every
 * [SmartPlaylistPredicate] variant without re-querying the database per row.
 *
 * `episodeTitle` is included up-front (not strictly needed for predicate evaluation)
 * because the editor screen (Task 9) renders a "matched preview" of the first few
 * episode titles under the live count — pulling it through the projection now
 * avoids reflowing the type later.
 */
data class EpisodeFacts(
    val episodeId: String,
    val episodeTitle: String,
    val podcastId: String,
    val publishedAtMs: Long,
    val durationSec: Int,
    val transcriptUrl: String?,
    val hasCachedTranscript: Boolean,
    val hasSnippets: Boolean,
    val isDownloaded: Boolean,
    val playState: PlayState,
)

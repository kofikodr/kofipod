// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.search

/**
 * One row in the Library search result list.
 *
 * Every variant carries enough metadata to render a row without a follow-up
 * query (episode + podcast titles, artwork) and enough routing info to deep
 * link the user to the source surface (episodeId always; timestampMs only
 * where a seek-to-position is meaningful).
 *
 * [excerpt] is FTS5's `snippet(...)` output — short text with `<<…>>` markers
 * around matched terms. The UI strips/replaces those markers when rendering.
 */
sealed interface LibrarySearchResult {
    val episodeId: String
    val episodeTitle: String
    val podcastId: String
    val podcastTitle: String
    val artworkUrl: String
    val excerpt: String

    data class BookmarkMatch(
        val bookmarkId: String,
        val timestampMs: Long,
        override val episodeId: String,
        override val episodeTitle: String,
        override val podcastId: String,
        override val podcastTitle: String,
        override val artworkUrl: String,
        override val excerpt: String,
    ) : LibrarySearchResult

    data class SummaryMatch(
        override val episodeId: String,
        override val episodeTitle: String,
        override val podcastId: String,
        override val podcastTitle: String,
        override val artworkUrl: String,
        override val excerpt: String,
    ) : LibrarySearchResult

    data class TranscriptMatch(
        override val episodeId: String,
        override val episodeTitle: String,
        override val podcastId: String,
        override val podcastTitle: String,
        override val artworkUrl: String,
        override val excerpt: String,
    ) : LibrarySearchResult
}

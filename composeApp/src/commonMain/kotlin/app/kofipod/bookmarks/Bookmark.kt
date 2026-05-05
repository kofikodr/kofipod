// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.bookmarks

data class Bookmark(
    val id: String,
    val episodeId: String,
    val podcastId: String,
    val timestampMs: Long,
    val note: String?,
    val createdAtMs: Long,
)

data class BookmarkWithContext(
    val bookmark: Bookmark,
    val episodeTitle: String,
    val podcastTitle: String,
    val artworkUrl: String,
)

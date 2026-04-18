// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.domain

import app.kofipod.db.Podcast
import com.mr3y.podcastindex.model.PodcastFeed

data class PodcastSummary(
    val id: String,
    val feedId: Long,
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String,
    val feedUrl: String,
)

fun PodcastFeed.toSummary(): PodcastSummary = PodcastSummary(
    id = id.toString(),
    feedId = id,
    title = title,
    author = author,
    description = description,
    artworkUrl = artwork.ifBlank { image },
    feedUrl = url,
)

fun Podcast.toSummary(): PodcastSummary = PodcastSummary(
    id = id,
    feedId = id.toLongOrNull() ?: 0L,
    title = title,
    author = author,
    description = description,
    artworkUrl = artworkUrl,
    feedUrl = feedUrl,
)

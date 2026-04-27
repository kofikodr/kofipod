// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.domain

import app.kofipod.db.Podcast
import com.mr3y.podcastindex.model.PodcastFeed
import com.mr3y.podcastindex.model.TrendingFeed
import kotlinx.serialization.Serializable

@Serializable
data class PodcastSummary(
    val id: String,
    val feedId: Long,
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String,
    val feedUrl: String,
    val category: String = "",
    val episodeCount: Int = 0,
    /** Podcast Index Category enum ids associated with this feed. Populated by the API
     *  conversions; empty when the source didn't expose categories. The recommender uses these
     *  for scoring; everything else displays only [category]. */
    val categoryIds: List<Int> = emptyList(),
)

fun PodcastFeed.toSummary(): PodcastSummary =
    PodcastSummary(
        id = id.toString(),
        feedId = id,
        title = title,
        author = author,
        description = description,
        artworkUrl = artwork.ifBlank { image },
        feedUrl = url,
        category = categories?.firstOrNull()?.label.orEmpty(),
        episodeCount = episodeCount,
        categoryIds = categories?.map { it.id }.orEmpty(),
    )

fun TrendingFeed.toSummary(): PodcastSummary =
    PodcastSummary(
        id = id.toString(),
        feedId = id,
        title = title,
        author = author,
        description = description,
        artworkUrl = artwork.ifBlank { image },
        feedUrl = url,
        category = categories?.firstOrNull()?.label.orEmpty(),
        episodeCount = 0,
        categoryIds = categories?.map { it.id }.orEmpty(),
    )

fun Podcast.toSummary(): PodcastSummary =
    PodcastSummary(
        id = id,
        feedId = id.toLongOrNull() ?: 0L,
        title = title,
        author = author,
        description = description,
        artworkUrl = artworkUrl,
        feedUrl = feedUrl,
        category = primaryCategory,
    )

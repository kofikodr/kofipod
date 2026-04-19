// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.kofipod.db.KofipodDatabase
import app.kofipod.db.RecentPodcastView
import app.kofipod.domain.PodcastSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RecentlyViewedRepository(private val db: KofipodDatabase) {
    fun recentExcludingSavedFlow(maxRows: Long = DEFAULT_MAX_ROWS): Flow<List<PodcastSummary>> =
        db.recentPodcastViewQueries.selectExcludingSaved(maxRows)
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toSummary() } }

    fun recordView(
        summary: PodcastSummary,
        viewedAt: Long,
    ) {
        db.recentPodcastViewQueries.upsert(
            id = summary.id,
            title = summary.title,
            author = summary.author,
            description = summary.description,
            artworkUrl = summary.artworkUrl,
            feedUrl = summary.feedUrl,
            category = summary.category,
            episodeCount = summary.episodeCount.toLong(),
            viewedAt = viewedAt,
        )
    }

    fun forget(id: String) = db.recentPodcastViewQueries.deleteById(id)

    companion object {
        const val DEFAULT_MAX_ROWS: Long = 8
    }
}

private fun RecentPodcastView.toSummary(): PodcastSummary =
    PodcastSummary(
        id = id,
        feedId = id.toLongOrNull() ?: 0L,
        title = title,
        author = author,
        description = description,
        artworkUrl = artworkUrl,
        feedUrl = feedUrl,
        category = category,
        episodeCount = episodeCount.toInt(),
    )

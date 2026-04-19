// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.kofipod.data.api.PodcastIndexApi
import app.kofipod.db.Episode
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface EpisodeSource {
    fun episodesFlow(podcastId: String): Flow<List<Episode>>

    /**
     * podcastId → count of "new" episodes (never-started AND published after the podcast
     * was added to library). Podcasts with zero new episodes are absent from the map.
     */
    fun newEpisodeCountsFlow(): Flow<Map<String, Int>>

    suspend fun refresh(
        podcastId: String,
        feedId: Long,
        nowMillis: Long,
    ): RefreshResult
}

data class RefreshResult(val inserted: Int, val totalRemote: Int)

class EpisodesRepository(
    private val db: KofipodDatabase,
    private val api: PodcastIndexApi,
) : EpisodeSource {
    override fun episodesFlow(podcastId: String): Flow<List<Episode>> =
        db.episodeQueries.selectByPodcast(podcastId).asFlow().mapToList(Dispatchers.Default)

    override fun newEpisodeCountsFlow(): Flow<Map<String, Int>> =
        db.episodeQueries
            .selectNewEpisodeCountsByPodcast()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.associate { it.podcastId to it.newCount.toInt() } }

    override suspend fun refresh(
        podcastId: String,
        feedId: Long,
        nowMillis: Long,
    ): RefreshResult {
        val existingGuids = db.episodeQueries.selectGuidsByPodcast(podcastId).executeAsList().toSet()
        val remote = api.episodesByFeedId(feedId)
        var inserted = 0
        for (ep in remote) {
            if (ep.guid in existingGuids) continue
            db.episodeQueries.insert(
                id = ep.id.toString(),
                podcastId = podcastId,
                guid = ep.guid,
                title = ep.title,
                description = ep.description.orEmpty(),
                // The Podcast Index API returns datePublished in Unix **seconds**, but the
                // podcastindex-sdk's Instant deserializer reads the raw number as milliseconds,
                // so ep.datePublished is 1000× too small (lands on 1970-01-21). Restore real ms.
                publishedAt = ep.datePublished.toEpochMilliseconds() * 1000L,
                durationSec = (ep.duration ?: 0).toLong(),
                enclosureUrl = ep.enclosureUrl,
                enclosureMimeType = ep.enclosureType,
                fileSizeBytes = ep.enclosureLength.toLong(),
                seasonNumber = ep.season?.toLong(),
                episodeNumber = ep.episode?.toLong(),
            )
            inserted++
        }
        db.podcastQueries.setLastChecked(nowMillis, podcastId)
        return RefreshResult(inserted = inserted, totalRemote = remote.size)
    }
}

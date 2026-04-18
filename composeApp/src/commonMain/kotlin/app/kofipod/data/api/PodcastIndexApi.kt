// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.api

import app.kofipod.config.BuildKonfig
import com.mr3y.podcastindex.PodcastIndexClient
import com.mr3y.podcastindex.ktor3.PodcastIndexClient
import com.mr3y.podcastindex.model.EpisodeFeed
import com.mr3y.podcastindex.model.PodcastFeed

class PodcastIndexApi(private val client: PodcastIndexClient) {

    suspend fun searchByTitle(query: String, limit: Int = DEFAULT_LIMIT): List<PodcastFeed> =
        client.search.forPodcastsByTitle(title = query, limit = limit)
            .feeds
            .filterContentTypes()

    suspend fun searchByTerm(query: String, limit: Int = DEFAULT_LIMIT): List<PodcastFeed> =
        client.search.forPodcastsByTerm(term = query, limit = limit)
            .feeds
            .filterContentTypes()

    suspend fun searchByPerson(person: String, limit: Int = DEFAULT_LIMIT): List<EpisodeFeed> =
        client.search.forEpisodesByPerson(name = person, limit = limit)
            .items

    suspend fun podcastByFeedId(feedId: Long): PodcastFeed =
        client.podcasts.byFeedId(id = feedId).feed

    suspend fun episodesByFeedId(feedId: Long, limit: Int = EPISODE_LIMIT): List<EpisodeFeed> =
        client.episodes.byFeedId(ids = listOf(feedId), limit = limit).items

    private fun List<PodcastFeed>.filterContentTypes(): List<PodcastFeed> =
        filter { feed ->
            val m = feed.medium?.lowercase()
            m != "music" && m != "musicl" && m != "audiobook"
        }

    companion object {
        const val PAGE_SIZE = 10
        const val DEFAULT_LIMIT = 30
        const val EPISODE_LIMIT = 50

        fun create(): PodcastIndexApi = PodcastIndexApi(
            PodcastIndexClient(
                authKey = BuildKonfig.PODCAST_INDEX_KEY,
                authSecret = BuildKonfig.PODCAST_INDEX_SECRET,
                userAgent = BuildKonfig.USER_AGENT,
            )
        )
    }
}

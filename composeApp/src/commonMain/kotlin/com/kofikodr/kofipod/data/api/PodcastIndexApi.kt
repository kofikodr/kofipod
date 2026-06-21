// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import com.mr3y.podcastindex.PodcastIndexClient
import com.mr3y.podcastindex.model.Category
import com.mr3y.podcastindex.model.EpisodeFeed
import com.mr3y.podcastindex.model.PodcastFeed

class PodcastIndexApi(private val clientProvider: PodcastIndexClientProvider<PodcastIndexClient>) {
    suspend fun searchByTitle(
        query: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<PodcastFeed> =
        clientProvider.get().search.forPodcastsByTitle(title = query, limit = limit)
            .feeds
            .filterContentTypes()

    suspend fun searchByTerm(
        query: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<PodcastFeed> =
        clientProvider.get().search.forPodcastsByTerm(term = query, limit = limit)
            .feeds
            .filterContentTypes()

    suspend fun searchByPerson(
        person: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<EpisodeFeed> =
        clientProvider.get().search.forEpisodesByPerson(name = person, limit = limit)
            .items

    suspend fun trending(
        limit: Int = DEFAULT_LIMIT,
        includeCategories: List<Category> = emptyList(),
    ): List<com.mr3y.podcastindex.model.TrendingFeed> =
        clientProvider.get().misc.getTrending(
            limit = limit,
            includeCategories = includeCategories,
        ).feeds

    suspend fun podcastByFeedId(feedId: Long): PodcastFeed = clientProvider.get().podcasts.byFeedId(id = feedId).feed

    suspend fun podcastByFeedUrl(url: String): PodcastFeed = clientProvider.get().podcasts.byFeedUrl(url = url).feed

    suspend fun episodesByFeedId(
        feedId: Long,
        limit: Int = EPISODE_LIMIT,
    ): List<EpisodeFeed> = clientProvider.get().episodes.byFeedId(ids = listOf(feedId), limit = limit).items

    private fun List<PodcastFeed>.filterContentTypes(): List<PodcastFeed> =
        filter { feed ->
            val m = feed.medium?.lowercase()
            m != "music" && m != "musicl" && m != "audiobook"
        }

    companion object {
        const val PAGE_SIZE = 10
        const val DEFAULT_LIMIT = 30
        const val EPISODE_LIMIT = 50
    }
}

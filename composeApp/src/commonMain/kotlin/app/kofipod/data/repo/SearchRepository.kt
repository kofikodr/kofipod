// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.kofipod.data.api.PodcastIndexApi
import app.kofipod.domain.PodcastSummary
import app.kofipod.domain.toSummary

interface SearchSource {
    suspend fun searchAll(
        query: String,
        limit: Int = PodcastIndexApi.PAGE_SIZE,
    ): List<PodcastSummary>

    suspend fun searchByTitle(
        query: String,
        limit: Int = PodcastIndexApi.PAGE_SIZE,
    ): List<PodcastSummary>

    suspend fun searchByPerson(
        name: String,
        limit: Int = PodcastIndexApi.PAGE_SIZE,
    ): List<PodcastSummary>
}

class SearchRepository(private val api: PodcastIndexApi) : SearchSource {
    override suspend fun searchAll(
        query: String,
        limit: Int,
    ): List<PodcastSummary> = api.searchByTerm(query, limit = limit).map { it.toSummary() }

    override suspend fun searchByTitle(
        query: String,
        limit: Int,
    ): List<PodcastSummary> = api.searchByTitle(query, limit = limit).map { it.toSummary() }

    override suspend fun searchByPerson(
        name: String,
        limit: Int,
    ): List<PodcastSummary> {
        val episodes = api.searchByPerson(name, limit = limit)
        val feedIds = episodes.map { it.feedId }.toSet()
        return feedIds.mapNotNull { feedId ->
            runCatching { api.podcastByFeedId(feedId).toSummary() }.getOrNull()
        }
    }
}

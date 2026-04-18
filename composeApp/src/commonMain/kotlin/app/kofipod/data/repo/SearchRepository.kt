// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.kofipod.data.api.PodcastIndexApi
import app.kofipod.domain.PodcastSummary
import app.kofipod.domain.toSummary

interface SearchSource {
    suspend fun searchAll(query: String): List<PodcastSummary>
    suspend fun searchByTitle(query: String): List<PodcastSummary>
    suspend fun searchByPerson(name: String): List<PodcastSummary>
}

class SearchRepository(private val api: PodcastIndexApi) : SearchSource {

    override suspend fun searchAll(query: String): List<PodcastSummary> =
        api.searchByTerm(query).map { it.toSummary() }

    override suspend fun searchByTitle(query: String): List<PodcastSummary> =
        api.searchByTitle(query).map { it.toSummary() }

    override suspend fun searchByPerson(name: String): List<PodcastSummary> {
        val episodes = api.searchByPerson(name)
        val feedIds = episodes.map { it.feedId }.toSet()
        return feedIds.mapNotNull { feedId ->
            runCatching { api.podcastByFeedId(feedId).toSummary() }.getOrNull()
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.repo

import com.kofikodr.kofipod.data.api.PodcastIndexApi
import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.domain.toSummary

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

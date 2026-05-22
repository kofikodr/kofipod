// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.repo

import com.kofikodr.kofipod.data.api.PodcastIndexApi
import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.domain.toSummary
import kotlinx.coroutines.CancellationException

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

/**
 * Thrown by a [SearchSource] when it cannot fulfil a particular search mode (e.g.
 * the iTunes API has no podcast-search-by-person endpoint). Distinct from a real
 * failure: [com.kofikodr.kofipod.data.repo.AggregateSearchSource] skips an
 * `UnsupportedSearchModeException` rather than counting it as a successful empty
 * result, so a peer source's genuine failure isn't masked as "no results".
 */
class UnsupportedSearchModeException(message: String) : RuntimeException(message)

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
            try {
                api.podcastByFeedId(feedId).toSummary()
            } catch (e: CancellationException) {
                // Person search is a two-stage call (episodes → fan-out feed lookups).
                // The aggregate-search timeout and `collectLatest`-driven query change
                // both cancel via CancellationException. `runCatching` would convert
                // those cancellations into a `null` and the caller would treat a
                // *cancelled* search as a successful "no shows for that person",
                // defeating the cancellation guarantees in AggregateSearchSource.
                throw e
            } catch (_: Throwable) {
                // Per-feed lookup failures are tolerable — drop the single feed and
                // keep the rest of the person results.
                null
            }
        }
    }
}

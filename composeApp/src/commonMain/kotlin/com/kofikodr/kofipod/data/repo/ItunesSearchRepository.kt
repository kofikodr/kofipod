// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.repo

import com.kofikodr.kofipod.data.api.ItunesSearchApi
import com.kofikodr.kofipod.data.search.ItunesStorefrontStore
import com.kofikodr.kofipod.domain.PodcastSummary

/**
 * Apple iTunes-backed [SearchSource]. The all-tab and title-tab routes hit the iTunes
 * Search API with the user's currently-picked storefront (read fresh on every call so
 * a Settings change applies on the next debounce, no app restart needed).
 *
 * The person-tab route **throws** [UnsupportedSearchModeException] — iTunes has no
 * podcast-search-by-person equivalent. The aggregator catches that specific exception
 * and routes it to `Outcome.Skipped` (distinct from `Failed`), so an iTunes "skip"
 * doesn't mask a real Podcast Index failure on the same query. Returning an empty
 * list would conflate those two cases.
 */
class ItunesSearchRepository(
    private val api: ItunesSearchApi,
    private val storefronts: ItunesStorefrontStore,
) : SearchSource {
    override suspend fun searchAll(
        query: String,
        limit: Int,
    ): List<PodcastSummary> =
        api.search(
            term = query,
            storefront = storefronts.currentNow(),
            limit = limit,
            attribute = ItunesSearchApi.SearchAttribute.Any,
        )

    override suspend fun searchByTitle(
        query: String,
        limit: Int,
    ): List<PodcastSummary> =
        api.search(
            term = query,
            storefront = storefronts.currentNow(),
            limit = limit,
            attribute = ItunesSearchApi.SearchAttribute.TitleTerm,
        )

    override suspend fun searchByPerson(
        name: String,
        limit: Int,
    ): List<PodcastSummary> =
        // iTunes has no podcast-search-by-person endpoint. Throwing here (instead of
        // returning emptyList()) lets AggregateSearchSource distinguish "this source
        // can't handle the query" from "this source ran and found nothing", so a
        // Podcast Index failure on the same call doesn't get masked as a successful
        // empty aggregate.
        throw UnsupportedSearchModeException("iTunes does not support search by person")
}

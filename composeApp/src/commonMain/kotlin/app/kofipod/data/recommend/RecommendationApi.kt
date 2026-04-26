// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.recommend

import app.kofipod.domain.PodcastSummary

/**
 * Narrow API surface the recommender needs from Podcast Index. Kept independent of the SDK
 * so the [RecommendationsRepository] can be unit-tested with a hand-rolled fake.
 */
interface RecommendationApi {
    /** Categories for a single podcast feed, or null on failure. */
    suspend fun fetchPodcastCategories(feedId: Long): List<Int>?

    /** Trending podcasts filtered to the given category ids. May be empty on failure. */
    suspend fun trending(
        includeCategoryIds: List<Int>,
        limit: Int,
    ): List<PodcastSummary>
}

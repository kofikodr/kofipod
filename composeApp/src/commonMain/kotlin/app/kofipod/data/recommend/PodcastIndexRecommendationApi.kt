// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.recommend

import app.kofipod.data.api.PodcastIndexApi
import app.kofipod.domain.PodcastSummary
import app.kofipod.domain.toSummary
import com.mr3y.podcastindex.model.Category
import kotlinx.coroutines.CancellationException

/**
 * Adapter binding [RecommendationApi] to the live Podcast Index SDK. Lives next to the
 * recommender (not under data/api) because it is an implementation detail of the
 * recommendation pipeline and should not be reused by other features.
 */
class PodcastIndexRecommendationApi(private val api: PodcastIndexApi) : RecommendationApi {
    override suspend fun fetchPodcastCategories(feedId: Long): List<Int>? =
        runCatching { api.podcastByFeedId(feedId).categories?.map { it.id } }
            .getOrElse { e ->
                if (e is CancellationException) throw e
                null
            }

    override suspend fun trending(
        includeCategoryIds: List<Int>,
        limit: Int,
    ): List<PodcastSummary> {
        val cats = includeCategoryIds.mapNotNull { id -> CATEGORY_BY_ID[id] }
        return api.trending(limit = limit, includeCategories = cats).map { it.toSummary() }
    }

    private companion object {
        val CATEGORY_BY_ID: Map<Int, Category> = Category.entries.associateBy { it.id }
    }
}

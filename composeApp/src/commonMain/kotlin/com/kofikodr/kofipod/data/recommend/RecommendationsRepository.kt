// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.recommend

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.kofikodr.kofipod.db.KofipodDatabase
import com.kofikodr.kofipod.domain.PodcastSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Clock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** What the UI sees: today's items (or null when no cache) and how many manual reshuffles
 *  the user can still trigger today. */
data class RecommendationsState(
    val items: List<PodcastSummary>?,
    val reshufflesRemaining: Int,
)

enum class ReshuffleResult { Done, OutOfQuota, NoData }

interface RecommendationsSource {
    fun observe(): Flow<RecommendationsState>

    suspend fun refreshIfStale()

    /** Manually re-draw with a different seed. Capped per-day. */
    suspend fun reshuffle(): ReshuffleResult
}

/**
 * Builds the daily "For You" shelf on the Search screen. Pulls a taste profile from
 * [KofipodDatabase] (listening sessions × library × cached PodcastCategory rows), fetches
 * candidates from [RecommendationApi], ranks + samples them, and persists a single-row
 * snapshot to RecommendationCache. Re-runs at most once per [epoch day][Clock] — same-day
 * calls are no-ops, and an API failure during refresh leaves the previous day's cache intact
 * rather than emitting an empty shelf. Manual [reshuffle] runs the same pipeline with a
 * different seed up to [MAX_DAILY_RESHUFFLES] times per day.
 */
class RecommendationsRepository(
    private val db: KofipodDatabase,
    private val api: RecommendationApi,
    private val clock: Clock = Clock.System,
    private val json: Json = DEFAULT_JSON,
) : RecommendationsSource {
    private val payloadSerializer = ListSerializer(PodcastSummary.serializer())

    override fun observe(): Flow<RecommendationsState> =
        db.recommendationCacheQueries
            .select()
            .asFlow()
            .mapToOneOrNull(Dispatchers.Default)
            .map { row ->
                val items =
                    row?.payloadJson?.let {
                        runCatching { json.decodeFromString(payloadSerializer, it) }.getOrNull()
                    }
                val today = currentEpochDay()
                val used = if (row?.computedEpochDay == today) row.reshufflesUsed.toInt() else 0
                RecommendationsState(items = items, reshufflesRemaining = (MAX_DAILY_RESHUFFLES - used).coerceAtLeast(0))
            }
            .distinctUntilChanged()

    override suspend fun refreshIfStale() {
        val today = currentEpochDay()
        val cached = db.recommendationCacheQueries.select().executeAsOneOrNull()
        if (cached != null && cached.computedEpochDay == today) return
        runPipeline(today = today, seed = today, reshufflesUsed = 0)
    }

    override suspend fun reshuffle(): ReshuffleResult {
        val today = currentEpochDay()
        val cached = db.recommendationCacheQueries.select().executeAsOneOrNull()
        val currentUsed = if (cached?.computedEpochDay == today) cached.reshufflesUsed.toInt() else 0
        if (currentUsed >= MAX_DAILY_RESHUFFLES) return ReshuffleResult.OutOfQuota
        val nextUsed = currentUsed + 1
        // Distinct seed per draw: today × (cap+1) + nextUsed gives every (day, attempt) pair a
        // unique seed and keeps consecutive reshuffles materially different.
        val seed = today * (MAX_DAILY_RESHUFFLES + 1L) + nextUsed
        return runPipeline(today = today, seed = seed, reshufflesUsed = nextUsed)
    }

    private suspend fun runPipeline(
        today: Long,
        seed: Long,
        reshufflesUsed: Int,
    ): ReshuffleResult {
        // 1. Listening signal limited to library podcasts.
        val library = db.podcastQueries.selectAll().executeAsList().map { it.id }.toSet()
        val listened: Map<String, Long> =
            db.listeningSessionQueries
                .selectTopPodcasts(maxRows = MAX_LISTENED_PODCASTS.toLong())
                .executeAsList()
                .filter { it.podcastId in library }
                .associate { it.podcastId to (it.seconds ?: 0L) }

        if (!RecommendationAlgorithm.hasSufficientHistory(listened)) return ReshuffleResult.NoData

        // 2. Lazy-fill PodcastCategory rows for any library podcast we haven't looked up yet.
        val categoriesByPodcast: Map<String, List<Int>> = ensureCategoriesFor(listened.keys)
        val profile =
            RecommendationAlgorithm.computeTasteProfile(
                listenedSeconds = listened,
                podcastCategories = categoriesByPodcast,
            ) ?: return ReshuffleResult.NoData

        // 3. Pick today's category mix — date-seeded rotation keeps it varied day to day.
        val topCats =
            profile.categoryWeights.entries
                .sortedByDescending { it.value }
                .take(MAX_TOP_CATEGORIES)
                .map { it.key }
        val drivers = RecommendationAlgorithm.pickDailyCategories(topCats, DAILY_CATEGORY_PICKS, seed)
        if (drivers.isEmpty()) return ReshuffleResult.NoData

        // 4. Fetch trending for each driver; bail out (keep prior cache) on API failure.
        val perDriverBatches: List<Pair<Int, List<PodcastSummary>>> =
            runCatching {
                drivers.map { catId ->
                    catId to api.trending(includeCategoryIds = listOf(catId), limit = TRENDING_LIMIT_PER_CAT)
                }
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                return ReshuffleResult.NoData
            }

        val summariesByFeed = mutableMapOf<Long, PodcastSummary>()
        for ((_, batch) in perDriverBatches) {
            for (s in batch) {
                if (s.id in library) continue
                summariesByFeed[s.feedId] = s
            }
        }
        if (summariesByFeed.isEmpty()) return ReshuffleResult.NoData

        // 5. Score + sample. Score on the candidate's actual categories — Podcast Index
        //    trending uses includeCategories as a hint, not a strict filter, so attributing
        //    the driver category to every result would give noise full weight.
        val candidateInputs =
            summariesByFeed.values.map { s ->
                CandidateInput(feedId = s.feedId, categoryIds = s.categoryIds)
            }
        val scored = RecommendationAlgorithm.rankCandidates(candidateInputs, profile)
        val pickedIds = RecommendationAlgorithm.sampleForToday(scored, targetCount = TARGET_COUNT, seed = seed)
        val finalList = pickedIds.mapNotNull { summariesByFeed[it] }
        if (finalList.isEmpty()) return ReshuffleResult.NoData

        // 6. Persist single-row snapshot.
        db.recommendationCacheQueries.upsert(
            computedEpochDay = today,
            payloadJson = json.encodeToString(payloadSerializer, finalList),
            reshufflesUsed = reshufflesUsed.toLong(),
        )
        return ReshuffleResult.Done
    }

    private suspend fun ensureCategoriesFor(libraryPodcastIds: Set<String>): Map<String, List<Int>> {
        val result = mutableMapOf<String, List<Int>>()
        for (id in libraryPodcastIds) {
            val cached = db.podcastCategoryQueries.selectByPodcast(id).executeAsList().map { it.toInt() }
            if (cached.isNotEmpty()) {
                // A sentinel-only row means "looked up, no real cats" — skip re-fetch and skip
                // this podcast in the profile. Real cats sit alongside or instead of the sentinel.
                val real = cached.filter { it != EMPTY_CATEGORY_SENTINEL }
                if (real.isNotEmpty()) result[id] = real
                continue
            }
            val feedId = id.toLongOrNull() ?: continue
            val fetched =
                runCatching { api.fetchPodcastCategories(feedId) }
                    .getOrElse { e ->
                        if (e is CancellationException) throw e
                        null
                    } ?: continue
            if (fetched.isEmpty()) {
                // Sentinel row prevents re-fetching this podcast on every refresh.
                db.podcastCategoryQueries.insert(podcastId = id, categoryId = EMPTY_CATEGORY_SENTINEL.toLong())
                continue
            }
            for (cat in fetched) {
                db.podcastCategoryQueries.insert(podcastId = id, categoryId = cat.toLong())
            }
            result[id] = fetched
        }
        return result
    }

    private fun currentEpochDay(): Long = clock.now().toEpochMilliseconds() / MILLIS_PER_DAY

    companion object {
        const val MAX_LISTENED_PODCASTS: Int = 50
        const val MAX_TOP_CATEGORIES: Int = 5
        const val DAILY_CATEGORY_PICKS: Int = 3
        const val TRENDING_LIMIT_PER_CAT: Int = 25
        const val TARGET_COUNT: Int = 24
        const val MAX_DAILY_RESHUFFLES: Int = 5

        /** Stored in PodcastCategory to record "looked up, the API returned no real categories"
         *  so we don't re-hit the API for that podcast on every refresh. Real Category ids are
         *  positive, so -1 will never collide.
         */
        const val EMPTY_CATEGORY_SENTINEL: Int = -1
        private const val MILLIS_PER_DAY: Long = 86_400_000L
        private val DEFAULT_JSON: Json = Json { ignoreUnknownKeys = true }
    }
}

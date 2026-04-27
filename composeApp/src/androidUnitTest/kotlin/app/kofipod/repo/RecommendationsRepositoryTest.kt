// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.repo

import app.kofipod.data.recommend.RecommendationApi
import app.kofipod.data.recommend.RecommendationsRepository
import app.kofipod.data.recommend.ReshuffleResult
import app.kofipod.db.KofipodDatabase
import app.kofipod.domain.PodcastSummary
import app.kofipod.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.Test
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecommendationsRepositoryTest {
    @Test
    fun `cold start - insufficient history emits null and writes no cache`() =
        runTest {
            val db = inMemoryDatabase()
            seedPodcast(db, id = "p1", title = "Show 1")
            // Only 10 minutes of one podcast → under the 30-min / 2-distinct threshold.
            seedSession(db, podcastId = "p1", title = "Show 1", seconds = 600)

            val api = FakeRecommendationApi()
            val repo = newRepo(db, api, today = 100L)

            repo.refreshIfStale()

            assertNull(repo.observe().first().items)
            assertEquals(0, api.fetchCategoriesCalls, "should not call API when history is thin")
            assertEquals(0, api.trendingCalls, "should not call trending when history is thin")
        }

    @Test
    fun `happy path - fetches categories, ranks trending, excludes library, persists cache`() =
        runTest {
            val db = inMemoryDatabase()
            // Library: two well-listened podcasts in different categories.
            seedPodcast(db, id = "1", title = "Tech Show")
            seedPodcast(db, id = "2", title = "Comedy Show")
            seedSession(db, "1", "Tech Show", seconds = 3600) // 60 min
            seedSession(db, "2", "Comedy Show", seconds = 1200) // 20 min

            val api =
                FakeRecommendationApi(
                    podcastCategories =
                        mapOf(
                            1L to listOf(CAT_TECH),
                            2L to listOf(CAT_COMEDY),
                        ),
                    trendingByCategory =
                        mapOf(
                            CAT_TECH to
                                listOf(
                                    summary("100", 100L, "Trending Tech A", listOf(CAT_TECH)),
                                    summary("101", 101L, "Trending Tech B", listOf(CAT_TECH)),
                                    // Already in library — must be filtered out.
                                    summary("1", 1L, "Tech Show", listOf(CAT_TECH)),
                                ),
                            CAT_COMEDY to
                                listOf(
                                    summary("200", 200L, "Trending Comedy A", listOf(CAT_COMEDY)),
                                ),
                        ),
                )
            val repo = newRepo(db, api, today = 200L)

            repo.refreshIfStale()

            val recs = repo.observe().first().items
            assertNotNull(recs)
            val ids = recs.map { it.id }.toSet()
            assertTrue("100" in ids, "expected tech recommendation present")
            assertTrue("200" in ids, "expected comedy recommendation present")
            assertTrue("1" !in ids, "library podcast must be excluded from recs")
            assertTrue("2" !in ids, "library podcast must be excluded from recs")
            // Cache row written.
            val cached = db.recommendationCacheQueries.select().executeAsOneOrNull()
            assertNotNull(cached)
            assertEquals(200L, cached.computedEpochDay)
        }

    @Test
    fun `cache hit - second refresh same day does not call API again`() =
        runTest {
            val db = inMemoryDatabase()
            seedPodcast(db, id = "1", title = "Tech Show")
            seedPodcast(db, id = "2", title = "Comedy Show")
            seedSession(db, "1", "Tech Show", seconds = 3600)
            seedSession(db, "2", "Comedy Show", seconds = 1200)

            val api =
                FakeRecommendationApi(
                    podcastCategories =
                        mapOf(1L to listOf(CAT_TECH), 2L to listOf(CAT_COMEDY)),
                    trendingByCategory =
                        mapOf(
                            CAT_TECH to listOf(summary("100", 100L, "Tech Rec", listOf(CAT_TECH))),
                            CAT_COMEDY to listOf(summary("200", 200L, "Comedy Rec", listOf(CAT_COMEDY))),
                        ),
                )
            val repo = newRepo(db, api, today = 300L)

            repo.refreshIfStale()
            val firstTrendingCalls = api.trendingCalls
            assertTrue(firstTrendingCalls > 0, "first refresh should hit trending")

            // Same day — no work expected.
            repo.refreshIfStale()
            assertEquals(firstTrendingCalls, api.trendingCalls, "same-day refresh must not re-fetch trending")
        }

    @Test
    fun `category lookup is cached - second refresh on a new day reuses PodcastCategory rows`() =
        runTest {
            val db = inMemoryDatabase()
            seedPodcast(db, id = "1", title = "Tech")
            seedPodcast(db, id = "2", title = "Comedy")
            seedSession(db, "1", "Tech", seconds = 3600)
            seedSession(db, "2", "Comedy", seconds = 1200)

            val api =
                FakeRecommendationApi(
                    podcastCategories =
                        mapOf(1L to listOf(CAT_TECH), 2L to listOf(CAT_COMEDY)),
                    trendingByCategory =
                        mapOf(
                            CAT_TECH to listOf(summary("100", 100L, "T", listOf(CAT_TECH))),
                            CAT_COMEDY to listOf(summary("200", 200L, "C", listOf(CAT_COMEDY))),
                        ),
                )
            val repo1 = newRepo(db, api, today = 400L)
            repo1.refreshIfStale()
            val firstFetchCalls = api.fetchCategoriesCalls
            assertEquals(2, firstFetchCalls, "should fetch categories for each library podcast once")

            // New day, but library unchanged — no new category lookups.
            val repo2 = newRepo(db, api, today = 401L)
            repo2.refreshIfStale()
            assertEquals(firstFetchCalls, api.fetchCategoriesCalls, "category cache should be reused")
        }

    @Test
    fun `category lookup returning empty - records sentinel so we never re-fetch that podcast`() =
        runTest {
            val db = inMemoryDatabase()
            // Two podcasts in library; one has real cats, the other returns empty from the API.
            seedPodcast(db, id = "1", title = "Tech")
            seedPodcast(db, id = "2", title = "Categoryless")
            seedSession(db, "1", "Tech", seconds = 3600)
            seedSession(db, "2", "Categoryless", seconds = 1200)

            val api =
                FakeRecommendationApi(
                    // Podcast 2 returns an empty list — legitimate "no categories assigned".
                    podcastCategories =
                        mapOf(
                            1L to listOf(CAT_TECH),
                            2L to emptyList(),
                        ),
                    trendingByCategory =
                        mapOf(CAT_TECH to listOf(summary("100", 100L, "Tech Rec", listOf(CAT_TECH)))),
                )
            val repo1 = newRepo(db, api, today = 600L)
            repo1.refreshIfStale()
            assertEquals(2, api.fetchCategoriesCalls, "should look up both podcasts on first run")

            // New day, library unchanged. The categoryless podcast must NOT be re-fetched —
            // the sentinel row should short-circuit the lookup.
            val repo2 = newRepo(db, api, today = 601L)
            repo2.refreshIfStale()
            assertEquals(2, api.fetchCategoriesCalls, "sentinel row must prevent re-fetch on next day")
        }

    @Test
    fun `off-topic noise from trending - on-topic candidates dominate the final list`() =
        runTest {
            val db = inMemoryDatabase()
            // Library is all Tech — profile will weight CAT_TECH = 1.0.
            seedPodcast(db, id = "1", title = "Tech 1")
            seedPodcast(db, id = "2", title = "Tech 2")
            seedSession(db, "1", "Tech 1", seconds = 3600)
            seedSession(db, "2", "Tech 2", seconds = 3600)

            // Real Podcast Index trending returns some off-category items even with a category
            // hint. The scoring + sampling layer should keep them out of the top of the list.
            val onTopic =
                (100..115L).map { fid ->
                    summary(fid.toString(), fid, "Tech Trending $fid", listOf(CAT_TECH))
                }
            // Noise has no categories (or unrelated ones the profile won't match) — represents
            // off-category items the trending endpoint can return when a hint is supplied.
            val noise =
                (200..209L).map { fid ->
                    summary(fid.toString(), fid, "Random $fid", emptyList())
                }
            val api =
                FakeRecommendationApi(
                    podcastCategories = mapOf(1L to listOf(CAT_TECH), 2L to listOf(CAT_TECH)),
                    trendingByCategory = mapOf(CAT_TECH to onTopic),
                    extraNoise = noise,
                )
            val repo = newRepo(db, api, today = 700L)

            repo.refreshIfStale()
            val recs = repo.observe().first().items
            assertNotNull(recs)
            // Noise scores 0.0 (no overlap with profile categories), so the sampler must filter
            // it out entirely — not merely deprioritize it.
            val noiseIds = noise.map { it.id }.toSet()
            assertTrue(
                recs.none { it.id in noiseIds },
                "off-topic noise must be excluded from recommendations, found: ${recs.filter { it.id in noiseIds }.map { it.id }}",
            )
        }

    @Test
    fun `corrupt payloadJson in cache - observe emits null instead of crashing`() =
        runTest {
            val db = inMemoryDatabase()
            // Hand-write a row with garbage JSON — simulates a partial write or future-schema row.
            db.recommendationCacheQueries.upsert(
                computedEpochDay = 999L,
                payloadJson = "not-valid-json",
                reshufflesUsed = 0L,
            )

            val api = FakeRecommendationApi()
            val repo = newRepo(db, api, today = 999L)

            assertNull(
                repo.observe().first().items,
                "deserialization failure must surface as null, not a thrown exception",
            )
        }

    @Test
    fun `reshuffle increments counter, surfaces new draw, and decrements remaining`() =
        runTest {
            val db = inMemoryDatabase()
            seedPodcast(db, id = "1", title = "Tech")
            seedPodcast(db, id = "2", title = "Comedy")
            seedSession(db, "1", "Tech", seconds = 3600)
            seedSession(db, "2", "Comedy", seconds = 1200)

            // Provide enough trending depth that two different seeds can produce different draws.
            val techPool = (100..130L).map { fid -> summary(fid.toString(), fid, "T$fid", listOf(CAT_TECH)) }
            val comedyPool = (200..230L).map { fid -> summary(fid.toString(), fid, "C$fid", listOf(CAT_COMEDY)) }
            val api =
                FakeRecommendationApi(
                    podcastCategories = mapOf(1L to listOf(CAT_TECH), 2L to listOf(CAT_COMEDY)),
                    trendingByCategory = mapOf(CAT_TECH to techPool, CAT_COMEDY to comedyPool),
                )
            val repo = newRepo(db, api, today = 800L)

            repo.refreshIfStale()
            val first = repo.observe().first()
            assertNotNull(first.items)
            assertEquals(RecommendationsRepository.MAX_DAILY_RESHUFFLES, first.reshufflesRemaining)

            assertEquals(ReshuffleResult.Done, repo.reshuffle())
            val second = repo.observe().first()
            assertNotNull(second.items)
            assertEquals(RecommendationsRepository.MAX_DAILY_RESHUFFLES - 1, second.reshufflesRemaining)
            // Different seed → list should not be identical to the auto-refresh draw.
            assertNotEquals(
                first.items!!.map { it.id },
                second.items!!.map { it.id },
                "reshuffle should produce a materially different draw from the daily refresh",
            )
        }

    @Test
    fun `reshuffle returns OutOfQuota at the cap and stops mutating cache`() =
        runTest {
            val db = inMemoryDatabase()
            seedPodcast(db, id = "1", title = "Tech")
            seedPodcast(db, id = "2", title = "Comedy")
            seedSession(db, "1", "Tech", seconds = 3600)
            seedSession(db, "2", "Comedy", seconds = 1200)

            val techPool = (100..130L).map { fid -> summary(fid.toString(), fid, "T$fid", listOf(CAT_TECH)) }
            val comedyPool = (200..230L).map { fid -> summary(fid.toString(), fid, "C$fid", listOf(CAT_COMEDY)) }
            val api =
                FakeRecommendationApi(
                    podcastCategories = mapOf(1L to listOf(CAT_TECH), 2L to listOf(CAT_COMEDY)),
                    trendingByCategory = mapOf(CAT_TECH to techPool, CAT_COMEDY to comedyPool),
                )
            val repo = newRepo(db, api, today = 900L)

            repo.refreshIfStale()
            // Burn through the daily cap.
            repeat(RecommendationsRepository.MAX_DAILY_RESHUFFLES) {
                assertEquals(ReshuffleResult.Done, repo.reshuffle())
            }
            assertEquals(0, repo.observe().first().reshufflesRemaining)

            val capped = repo.observe().first()
            assertEquals(ReshuffleResult.OutOfQuota, repo.reshuffle())
            // Cache row should be unchanged after a no-op reshuffle.
            val afterCap = repo.observe().first()
            assertEquals(capped.items?.map { it.id }, afterCap.items?.map { it.id })
        }

    @Test
    fun `day rollover resets reshuffle quota`() =
        runTest {
            val db = inMemoryDatabase()
            seedPodcast(db, id = "1", title = "Tech")
            seedPodcast(db, id = "2", title = "Comedy")
            seedSession(db, "1", "Tech", seconds = 3600)
            seedSession(db, "2", "Comedy", seconds = 1200)

            val techPool = (100..130L).map { fid -> summary(fid.toString(), fid, "T$fid", listOf(CAT_TECH)) }
            val comedyPool = (200..230L).map { fid -> summary(fid.toString(), fid, "C$fid", listOf(CAT_COMEDY)) }
            val api =
                FakeRecommendationApi(
                    podcastCategories = mapOf(1L to listOf(CAT_TECH), 2L to listOf(CAT_COMEDY)),
                    trendingByCategory = mapOf(CAT_TECH to techPool, CAT_COMEDY to comedyPool),
                )

            val repo1 = newRepo(db, api, today = 1000L)
            repo1.refreshIfStale()
            repeat(RecommendationsRepository.MAX_DAILY_RESHUFFLES) { repo1.reshuffle() }
            assertEquals(ReshuffleResult.OutOfQuota, repo1.reshuffle())

            // New day. The next refresh writes a fresh cache row with reshufflesUsed = 0.
            val repo2 = newRepo(db, api, today = 1001L)
            repo2.refreshIfStale()
            assertEquals(
                RecommendationsRepository.MAX_DAILY_RESHUFFLES,
                repo2.observe().first().reshufflesRemaining,
                "quota must reset on a new day",
            )
            assertEquals(ReshuffleResult.Done, repo2.reshuffle())
        }

    @Test
    fun `api failure on trending - keeps prior cache instead of clobbering it`() =
        runTest {
            val db = inMemoryDatabase()
            seedPodcast(db, id = "1", title = "T")
            seedPodcast(db, id = "2", title = "C")
            seedSession(db, "1", "T", seconds = 3600)
            seedSession(db, "2", "C", seconds = 1200)

            val api =
                FakeRecommendationApi(
                    podcastCategories = mapOf(1L to listOf(CAT_TECH), 2L to listOf(CAT_COMEDY)),
                    trendingByCategory =
                        mapOf(
                            CAT_TECH to listOf(summary("100", 100L, "Tech Rec", listOf(CAT_TECH))),
                            CAT_COMEDY to listOf(summary("200", 200L, "Comedy Rec", listOf(CAT_COMEDY))),
                        ),
                )
            val repo1 = newRepo(db, api, today = 500L)
            repo1.refreshIfStale()
            val cached = repo1.observe().first().items
            assertNotNull(cached)

            // Next day, the API throws.
            api.trendingShouldThrow = true
            val repo2 = newRepo(db, api, today = 501L)
            repo2.refreshIfStale()
            // Previous cache still observable.
            assertEquals(cached.map { it.id }, repo2.observe().first().items?.map { it.id })
        }

    // ---- helpers ----

    private fun newRepo(
        db: KofipodDatabase,
        api: RecommendationApi,
        today: Long,
    ): RecommendationsRepository =
        RecommendationsRepository(
            db = db,
            api = api,
            clock = FixedClock(epochDay = today),
        )

    private fun seedPodcast(
        db: KofipodDatabase,
        id: String,
        title: String,
    ) {
        db.podcastQueries.insert(
            id = id,
            title = title,
            author = "",
            description = "",
            artworkUrl = "",
            feedUrl = "",
            listId = null,
            autoDownloadEnabled = 0,
            notifyNewEpisodesEnabled = 1,
            lastCheckedAt = null,
            addedAt = 0L,
            primaryCategory = "",
        )
    }

    private fun seedSession(
        db: KofipodDatabase,
        podcastId: String,
        title: String,
        seconds: Long,
    ) {
        db.listeningSessionQueries.setRow(
            epochDay = 0L,
            podcastId = podcastId,
            podcastTitle = title,
            secondsListened = seconds,
        )
    }

    private fun summary(
        id: String,
        feedId: Long,
        title: String,
        categoryIds: List<Int> = emptyList(),
    ): PodcastSummary =
        PodcastSummary(
            id = id,
            feedId = feedId,
            title = title,
            author = "",
            description = "",
            artworkUrl = "",
            feedUrl = "",
            categoryIds = categoryIds,
        )

    companion object {
        const val CAT_TECH = 102 // Category.TECHNOLOGY's id is irrelevant for the test
        const val CAT_COMEDY = 16
    }
}

private class FakeRecommendationApi(
    val podcastCategories: Map<Long, List<Int>> = emptyMap(),
    val trendingByCategory: Map<Int, List<PodcastSummary>> = emptyMap(),
    /** Off-topic podcasts surfaced in every trending batch — the real Podcast Index trending
     *  endpoint uses `includeCategories` as a hint, not a strict filter, so realistic results
     *  contain irrelevant podcasts that the scoring layer must deprioritize. */
    val extraNoise: List<PodcastSummary> = emptyList(),
) : RecommendationApi {
    var fetchCategoriesCalls: Int = 0
        private set
    var trendingCalls: Int = 0
        private set
    var trendingShouldThrow: Boolean = false

    override suspend fun fetchPodcastCategories(feedId: Long): List<Int>? {
        fetchCategoriesCalls += 1
        return podcastCategories[feedId]
    }

    override suspend fun trending(
        includeCategoryIds: List<Int>,
        limit: Int,
    ): List<PodcastSummary> {
        trendingCalls += 1
        // IOException (a non-RuntimeException) mirrors what Ktor actually throws on network
        // failure — guards the production catch from regressing back to `catch (RuntimeException)`.
        if (trendingShouldThrow) throw IOException("simulated network failure")
        val onTopic = includeCategoryIds.flatMap { trendingByCategory[it].orEmpty() }
        return (onTopic + extraNoise).distinctBy { it.id }
    }
}

private class FixedClock(private val epochDay: Long) : Clock {
    override fun now(): Instant = Instant.fromEpochMilliseconds(epochDay * 86_400_000L)
}

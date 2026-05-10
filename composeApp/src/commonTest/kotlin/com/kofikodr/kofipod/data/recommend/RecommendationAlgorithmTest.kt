// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.recommend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecommendationAlgorithmTest {
    // ---- hasSufficientHistory ----

    @Test
    fun `under 30 minutes total returns false`() {
        val listened = mapOf("p1" to 1500L, "p2" to 200L) // 1700s = ~28 min
        assertEquals(false, RecommendationAlgorithm.hasSufficientHistory(listened))
    }

    @Test
    fun `at least 30 minutes across two distinct podcasts returns true`() {
        val listened = mapOf("p1" to 1200L, "p2" to 600L) // 30 min, 2 podcasts
        assertEquals(true, RecommendationAlgorithm.hasSufficientHistory(listened))
    }

    @Test
    fun `60 minutes from a single podcast returns false (need at least 2 distinct)`() {
        val listened = mapOf("p1" to 3600L)
        assertEquals(false, RecommendationAlgorithm.hasSufficientHistory(listened))
    }

    @Test
    fun `empty history returns false`() {
        assertEquals(false, RecommendationAlgorithm.hasSufficientHistory(emptyMap()))
    }

    // ---- computeTasteProfile ----

    @Test
    fun `single podcast with one category produces 100 percent weight on that category`() {
        val profile =
            RecommendationAlgorithm.computeTasteProfile(
                listenedSeconds = mapOf("p1" to 1000L, "p2" to 500L),
                podcastCategories = mapOf("p1" to listOf(10), "p2" to listOf(10)),
            )
        assertNotNull(profile)
        assertEquals(1, profile.categoryWeights.size)
        assertEquals(1.0, profile.categoryWeights.getValue(10), 0.0001)
    }

    @Test
    fun `weights are proportional to seconds-listened across distinct categories`() {
        // p1: 750s in cat 10, p2: 250s in cat 20 → 75/25 split
        val profile =
            RecommendationAlgorithm.computeTasteProfile(
                listenedSeconds = mapOf("p1" to 750L, "p2" to 250L),
                podcastCategories = mapOf("p1" to listOf(10), "p2" to listOf(20)),
            )
        assertNotNull(profile)
        assertEquals(0.75, profile.categoryWeights.getValue(10), 0.0001)
        assertEquals(0.25, profile.categoryWeights.getValue(20), 0.0001)
    }

    @Test
    fun `multi-category podcast splits its seconds evenly across its categories`() {
        // p1: 600s with 2 categories → 300s each. p2: 400s in cat 30.
        // Total weighted: cat 10 = 300, cat 20 = 300, cat 30 = 400. Sum = 1000.
        val profile =
            RecommendationAlgorithm.computeTasteProfile(
                listenedSeconds = mapOf("p1" to 600L, "p2" to 400L),
                podcastCategories = mapOf("p1" to listOf(10, 20), "p2" to listOf(30)),
            )
        assertNotNull(profile)
        assertEquals(0.30, profile.categoryWeights.getValue(10), 0.0001)
        assertEquals(0.30, profile.categoryWeights.getValue(20), 0.0001)
        assertEquals(0.40, profile.categoryWeights.getValue(30), 0.0001)
    }

    @Test
    fun `weights always normalize to 1`() {
        val profile =
            RecommendationAlgorithm.computeTasteProfile(
                listenedSeconds = mapOf("p1" to 123L, "p2" to 456L, "p3" to 789L),
                podcastCategories =
                    mapOf(
                        "p1" to listOf(1, 2, 3),
                        "p2" to listOf(2),
                        "p3" to listOf(3, 4),
                    ),
            )
        assertNotNull(profile)
        val sum = profile.categoryWeights.values.sum()
        assertEquals(1.0, sum, 0.0001)
    }

    @Test
    fun `podcast with no known categories is excluded from the profile`() {
        // p2 has no categories — its 500s contribute nothing. Only p1 counts.
        val profile =
            RecommendationAlgorithm.computeTasteProfile(
                listenedSeconds = mapOf("p1" to 1000L, "p2" to 500L),
                podcastCategories = mapOf("p1" to listOf(10), "p2" to emptyList()),
            )
        assertNotNull(profile)
        assertEquals(1, profile.categoryWeights.size)
        assertEquals(1.0, profile.categoryWeights.getValue(10), 0.0001)
    }

    @Test
    fun `podcast with playtime but missing from categories map is excluded`() {
        // Mirrors the "removed from library" case: listening session exists, no categories
        val profile =
            RecommendationAlgorithm.computeTasteProfile(
                listenedSeconds = mapOf("p1" to 1000L, "removed" to 9999L),
                podcastCategories = mapOf("p1" to listOf(10)),
            )
        assertNotNull(profile)
        assertEquals(1, profile.categoryWeights.size)
        assertEquals(1.0, profile.categoryWeights.getValue(10), 0.0001)
    }

    @Test
    fun `topN caps how many podcasts contribute to the profile`() {
        // 3 podcasts but topN=2 → only the two most-listened (by seconds desc) count.
        val profile =
            RecommendationAlgorithm.computeTasteProfile(
                listenedSeconds = mapOf("p1" to 1000L, "p2" to 500L, "p3" to 100L),
                podcastCategories =
                    mapOf("p1" to listOf(10), "p2" to listOf(20), "p3" to listOf(30)),
                topN = 2,
            )
        assertNotNull(profile)
        // p3's category 30 should not appear.
        assertEquals(false, profile.categoryWeights.containsKey(30))
        assertEquals(2, profile.categoryWeights.size)
    }

    @Test
    fun `empty input returns null profile`() {
        assertNull(
            RecommendationAlgorithm.computeTasteProfile(
                listenedSeconds = emptyMap(),
                podcastCategories = emptyMap(),
            ),
        )
    }

    @Test
    fun `all podcasts unknown returns null profile`() {
        // Non-empty seconds, but no podcast has categories → no signal at all.
        assertNull(
            RecommendationAlgorithm.computeTasteProfile(
                listenedSeconds = mapOf("p1" to 1000L),
                podcastCategories = emptyMap(),
            ),
        )
    }

    // ---- rankCandidates ----

    @Test
    fun `candidate score equals sum of profile weights for matching categories`() {
        val profile = TasteProfile(mapOf(10 to 0.5, 20 to 0.3, 30 to 0.2))
        // Expected scores: 1L=0.5, 2L=0.8, 3L=0.0.
        val candidates =
            listOf(
                CandidateInput(feedId = 1L, categoryIds = listOf(10)),
                CandidateInput(feedId = 2L, categoryIds = listOf(10, 20)),
                CandidateInput(feedId = 3L, categoryIds = listOf(40)),
            )
        val scored = RecommendationAlgorithm.rankCandidates(candidates, profile).associateBy { it.feedId }
        assertEquals(0.5, scored.getValue(1L).score, 0.0001)
        assertEquals(0.8, scored.getValue(2L).score, 0.0001)
        assertEquals(0.0, scored.getValue(3L).score, 0.0001)
    }

    @Test
    fun `ranked output is ordered by score descending`() {
        val profile = TasteProfile(mapOf(10 to 0.5, 20 to 0.5))
        // Expected scores: 1L=0.5, 2L=1.0, 3L=0.5, 4L=0.0.
        val candidates =
            listOf(
                CandidateInput(1L, listOf(10)),
                CandidateInput(2L, listOf(10, 20)),
                CandidateInput(3L, listOf(20)),
                CandidateInput(4L, listOf(99)),
            )
        val ranked = RecommendationAlgorithm.rankCandidates(candidates, profile)
        // Scores are non-increasing across the ranked list.
        for ((a, b) in ranked.zipWithNext()) {
            assertTrue(a.score >= b.score, "scores should be non-increasing: ${a.score} vs ${b.score}")
        }
        // The top candidate is the only one with score 1.0.
        assertEquals(2L, ranked.first().feedId)
        // The zero-score candidate is last.
        assertEquals(4L, ranked.last().feedId)
    }

    // ---- sampleForToday ----

    @Test
    fun `sample returns at most targetCount items`() {
        val scored = (1..50L).map { ScoredCandidate(feedId = it, score = it.toDouble()) }
        val out = RecommendationAlgorithm.sampleForToday(scored, targetCount = 24, seed = 1L)
        assertEquals(24, out.size)
    }

    @Test
    fun `sample returns all items when there are fewer than targetCount`() {
        val scored = (1..10L).map { ScoredCandidate(feedId = it, score = it.toDouble()) }
        val out = RecommendationAlgorithm.sampleForToday(scored, targetCount = 24, seed = 1L)
        assertEquals(10, out.size)
        // All input ids present, no duplicates.
        assertEquals((1L..10L).toSet(), out.toSet())
    }

    @Test
    fun `different seeds produce a different ordering on the same scored pool`() {
        val scored = (1..50L).map { ScoredCandidate(feedId = it, score = it.toDouble()) }
        val a = RecommendationAlgorithm.sampleForToday(scored, targetCount = 24, seed = 1L)
        val b = RecommendationAlgorithm.sampleForToday(scored, targetCount = 24, seed = 2L)
        assertNotEquals(a, b, "expected different seeds to produce different draws")
    }

    @Test
    fun `same seed is deterministic`() {
        val scored = (1..50L).map { ScoredCandidate(feedId = it, score = it.toDouble()) }
        val a = RecommendationAlgorithm.sampleForToday(scored, targetCount = 24, seed = 7L)
        val b = RecommendationAlgorithm.sampleForToday(scored, targetCount = 24, seed = 7L)
        assertEquals(a, b)
    }

    @Test
    fun `sample never returns duplicates`() {
        val scored = (1..50L).map { ScoredCandidate(feedId = it, score = it.toDouble()) }
        val out = RecommendationAlgorithm.sampleForToday(scored, targetCount = 24, seed = 1L)
        assertEquals(out.size, out.toSet().size)
    }

    @Test
    fun `sample drops zero-score candidates so unrelated noise never surfaces`() {
        val scored =
            (1..30L).map { ScoredCandidate(feedId = it, score = 1.0) } +
                (100..120L).map { ScoredCandidate(feedId = it, score = 0.0) }
        val out = RecommendationAlgorithm.sampleForToday(scored, targetCount = 24, seed = 1L)
        // No id from the zero-score block should appear.
        assertTrue(out.all { it < 100L }, "zero-score candidates must not be sampled: $out")
    }

    @Test
    fun `empty pool returns empty result`() {
        val out = RecommendationAlgorithm.sampleForToday(emptyList(), targetCount = 24, seed = 1L)
        assertEquals(emptyList(), out)
    }

    // ---- pickDailyCategories ----

    @Test
    fun `pickDailyCategories returns at most pickCount and only from the input`() {
        val top = listOf(10, 20, 30, 40, 50)
        val picked = RecommendationAlgorithm.pickDailyCategories(top, pickCount = 3, seed = 42L)
        assertEquals(3, picked.size)
        assertTrue(picked.all { it in top })
        assertEquals(picked.size, picked.toSet().size, "picks should be unique")
    }

    @Test
    fun `pickDailyCategories returns all when pickCount exceeds list size`() {
        val top = listOf(10, 20)
        val picked = RecommendationAlgorithm.pickDailyCategories(top, pickCount = 5, seed = 1L)
        assertEquals(setOf(10, 20), picked.toSet())
    }

    @Test
    fun `pickDailyCategories rotates with seed`() {
        val top = listOf(10, 20, 30, 40, 50)
        val a = RecommendationAlgorithm.pickDailyCategories(top, pickCount = 3, seed = 1L)
        val b = RecommendationAlgorithm.pickDailyCategories(top, pickCount = 3, seed = 2L)
        // Hard guarantee: at least one different element across the two days.
        assertNotEquals(a.toSet(), b.toSet())
    }

    // ---- Golden-value tests pin the contract so a regression that drops the seed input
    //      (e.g. `Random.Default` instead of `Random(seed)`) cannot pass silently. ----

    @Test
    fun `pickDailyCategories - golden values for consecutive seeds preserve ordered window`() {
        val top = listOf(10, 20, 30, 40, 50)
        // Day-stable rotation: same seed → same picks, consecutive seeds slide the window by one.
        assertEquals(listOf(10, 20, 30), RecommendationAlgorithm.pickDailyCategories(top, 3, seed = 0L))
        assertEquals(listOf(20, 30, 40), RecommendationAlgorithm.pickDailyCategories(top, 3, seed = 1L))
        assertEquals(listOf(30, 40, 50), RecommendationAlgorithm.pickDailyCategories(top, 3, seed = 2L))
    }

    @Test
    fun `pickDailyCategories - wrap-around picks from the head once start exceeds n - k`() {
        val top = listOf(10, 20, 30, 40, 50)
        // seed 3 → start=3 → [40, 50, 10]: the [10] is the wrap-around.
        assertEquals(listOf(40, 50, 10), RecommendationAlgorithm.pickDailyCategories(top, 3, seed = 3L))
        // seed 4 → start=4 → [50, 10, 20]: two wrapped elements.
        assertEquals(listOf(50, 10, 20), RecommendationAlgorithm.pickDailyCategories(top, 3, seed = 4L))
        // Seed 5 wraps the start back to 0.
        assertEquals(listOf(10, 20, 30), RecommendationAlgorithm.pickDailyCategories(top, 3, seed = 5L))
    }

    @Test
    fun `pickDailyCategories - negative seeds are normalized into the positive range`() {
        val top = listOf(10, 20, 30, 40, 50)
        // -1 mod 5 normalized = 4 → same as seed 4.
        assertEquals(
            RecommendationAlgorithm.pickDailyCategories(top, 3, seed = 4L),
            RecommendationAlgorithm.pickDailyCategories(top, 3, seed = -1L),
        )
    }

    @Test
    fun `sampleForToday - golden draw for fixed pool and seed`() {
        // Pool of 10 with strictly increasing scores. Default headSize=36 caps to all 10, then
        // weighted-reservoir samples 5 with seed=7. Locked output catches any change to the RNG,
        // the keying formula, or the seed input being silently dropped.
        val scored = (1..10L).map { ScoredCandidate(feedId = it, score = it.toDouble()) }
        val out = RecommendationAlgorithm.sampleForToday(scored, targetCount = 5, seed = 7L)
        assertEquals(GOLDEN_SAMPLE_SEED_7, out)
    }

    companion object {
        // Captured from the current implementation. Update only when the algorithm intentionally
        // changes (and document the change in the same commit).
        private val GOLDEN_SAMPLE_SEED_7: List<Long> = listOf(10L, 4L, 7L, 9L, 8L)
    }
}

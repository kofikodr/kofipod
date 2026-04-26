// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.recommend

import kotlin.math.ln
import kotlin.random.Random

data class TasteProfile(
    val categoryWeights: Map<Int, Double>,
)

data class CandidateInput(
    val feedId: Long,
    val categoryIds: List<Int>,
)

data class ScoredCandidate(
    val feedId: Long,
    val score: Double,
)

object RecommendationAlgorithm {
    const val MIN_TOTAL_SECONDS: Long = 1800L
    const val MIN_DISTINCT_PODCASTS: Int = 2
    const val DEFAULT_TOP_N: Int = 20
    const val DEFAULT_HEAD_SIZE: Int = 36

    fun hasSufficientHistory(listenedSeconds: Map<String, Long>): Boolean {
        val total = listenedSeconds.values.sum()
        val distinct = listenedSeconds.count { it.value > 0L }
        return total >= MIN_TOTAL_SECONDS && distinct >= MIN_DISTINCT_PODCASTS
    }

    fun computeTasteProfile(
        listenedSeconds: Map<String, Long>,
        podcastCategories: Map<String, List<Int>>,
        topN: Int = DEFAULT_TOP_N,
    ): TasteProfile? {
        if (listenedSeconds.isEmpty()) return null
        val ranked =
            listenedSeconds.entries
                .asSequence()
                .filter { it.value > 0L }
                .sortedByDescending { it.value }
                .take(topN)
                .filter { (id, _) -> podcastCategories[id]?.isNotEmpty() == true }
                .toList()
        if (ranked.isEmpty()) return null

        val raw = mutableMapOf<Int, Double>()
        for ((id, sec) in ranked) {
            val cats = podcastCategories.getValue(id)
            val perCat = sec.toDouble() / cats.size
            for (c in cats) raw[c] = (raw[c] ?: 0.0) + perCat
        }
        val total = raw.values.sum()
        if (total <= 0.0) return null
        return TasteProfile(raw.mapValues { it.value / total })
    }

    fun rankCandidates(
        candidates: List<CandidateInput>,
        profile: TasteProfile,
    ): List<ScoredCandidate> =
        candidates
            .map { c ->
                val score = c.categoryIds.sumOf { profile.categoryWeights[it] ?: 0.0 }
                ScoredCandidate(c.feedId, score)
            }
            .sortedByDescending { it.score }

    /**
     * Weighted reservoir sampling (Efraimidis–Spirakis). Caps the candidate pool to
     * [headSize] to keep recommendations head-of-distribution, then draws [targetCount]
     * with probability proportional to score. Deterministic given the same [seed].
     */
    fun sampleForToday(
        scored: List<ScoredCandidate>,
        targetCount: Int,
        seed: Long,
        headSize: Int = DEFAULT_HEAD_SIZE,
    ): List<Long> {
        if (scored.isEmpty() || targetCount <= 0) return emptyList()
        val nonZero = scored.filter { it.score > 0.0 }
        if (nonZero.isEmpty()) return emptyList()

        val poolCap = maxOf(headSize, targetCount)
        val pool = nonZero.sortedByDescending { it.score }.take(poolCap)
        if (pool.size <= targetCount) return pool.map { it.feedId }

        val rng = Random(seed)
        return pool
            .map { c ->
                val u = rng.nextDouble().coerceAtLeast(MIN_UNIFORM)
                val key = -ln(u) / c.score
                key to c
            }
            .sortedBy { it.first }
            .take(targetCount)
            .map { it.second.feedId }
    }

    /**
     * Picks [pickCount] categories from the front of [topCategories] starting at a seed-
     * rotated offset. Day-stable (same seed → same picks) and rotates across consecutive
     * seeds, which we exploit to keep daily recommendations feeling fresh.
     */
    fun pickDailyCategories(
        topCategories: List<Int>,
        pickCount: Int,
        seed: Long,
    ): List<Int> {
        if (topCategories.isEmpty() || pickCount <= 0) return emptyList()
        val n = topCategories.size
        if (n <= pickCount) return topCategories
        val k = pickCount.coerceAtMost(n)
        val start = ((seed % n + n) % n).toInt()
        return List(k) { topCategories[(start + it) % n] }
    }

    private const val MIN_UNIFORM: Double = 1e-12
}

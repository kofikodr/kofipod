// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.search

import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.domain.SourceId

/**
 * Deduplicates and ranks results returned by [com.kofikodr.kofipod.data.repo.AggregateSearchSource]
 * after fanning out to multiple [com.kofikodr.kofipod.data.repo.SearchSource]s.
 *
 * Pure functions — testable in isolation, no Koin / DB / network.
 *
 * Dedup strategy:
 *  - Group by [FeedUrlCanonicalizer]'d `feedUrl`.
 *  - When two groups would still be distinct but clearly describe the same podcast,
 *    a secondary `(title, author)` pass merges them.
 *
 * Merge strategy (within a group):
 *  - Union the [PodcastSummary.sources] sets — that's the "this podcast was seen by
 *    both indexes" quality signal.
 *  - Prefer the Podcast Index entry's identity fields (`id`, `feedId`, `feedUrl`)
 *    because everything downstream — episode loading, recommendations, the DB —
 *    keys off Podcast Index's numeric `feedId`. Falling back to iTunes' identity
 *    would force tap-time hydration even when the user picked a result we already
 *    know.
 *  - Preserve the richest display fields (longest non-blank string per attribute).
 *  - Union [PodcastSummary.categoryIds] across sources so the recommender's
 *    category signal isn't degraded by picking only one source's view.
 *
 * Ranking:
 *  - Each summary keeps a per-source rank position (its 0-based position in that
 *    source's own result list).
 *  - The merged result's score is the best (lowest) position across its sources,
 *    minus a small "multi-source agreement" boost. Lower score = higher rank.
 */
object SearchResultMerger {
    /**
     * Bonus applied per additional source beyond the first. With two sources both
     * returning a podcast at position 3, the score becomes `3 - 0.5 = 2.5`, beating
     * a position-2 single-source result and reflecting the higher confidence.
     */
    private const val MULTI_SOURCE_BOOST_PER_EXTRA_SOURCE: Double = 0.5

    fun mergeAndRank(raw: List<RankedResult>): List<PodcastSummary> {
        if (raw.isEmpty()) return emptyList()

        val byFeedUrl = LinkedHashMap<String, MutableList<RankedResult>>()
        for (r in raw) {
            val key = FeedUrlCanonicalizer.canonicalize(r.summary.feedUrl)
            byFeedUrl.getOrPut(key) { mutableListOf() }.add(r)
        }

        // Secondary pass: merge groups whose canonical feedUrl differs but whose
        // (title, author) match exactly. This catches host redirects (www. vs not,
        // CDN swaps) without us having to guess at host equivalence rules.
        val mergedByIdentity = mergeByTitleAuthor(byFeedUrl.values.toList())

        return mergedByIdentity
            .map { it.collapse() }
            .sortedWith(
                // Primary: score. The multi-source agreement boost is already baked
                // into the score, so this selector reflects the merger's intent —
                // "best rank position, biased toward podcasts both sources confirmed".
                // Secondary: -sourceCount, only as a tiebreak when two results land at
                // identical scores.
                compareBy(
                    { it.score },
                    { -it.sourceCount },
                ),
            )
            .map { it.summary }
    }

    private fun mergeByTitleAuthor(groups: List<List<RankedResult>>): List<List<RankedResult>> {
        if (groups.size < 2) return groups
        val byIdentity = LinkedHashMap<IdentityKey?, MutableList<RankedResult>>()
        for (group in groups) {
            val key = identityKey(group)
            if (key == null) {
                // No usable identity (blank title or author) — keep the group as its
                // own bucket so we don't accidentally merge unrelated unnamed feeds.
                byIdentity[GroupSelf(group.first().summary.feedUrl)] = group.toMutableList()
            } else {
                byIdentity.getOrPut(key) { mutableListOf() }.addAll(group)
            }
        }
        return byIdentity.values.toList()
    }

    private fun identityKey(group: List<RankedResult>): IdentityKey? {
        val first = group.firstOrNull() ?: return null
        val title = first.summary.title.trim()
        val author = first.summary.author.trim()
        if (title.isEmpty() || author.isEmpty()) return null
        return TitleAuthor(title.lowercase(), author.lowercase())
    }

    private fun List<RankedResult>.collapse(): Collapsed {
        // Prefer the Podcast Index entry's identity if any source in the group is PI.
        val piEntry = firstOrNull { SourceId.PodcastIndex in it.summary.sources }
        val primary = piEntry ?: first()

        val sources = flatMapTo(LinkedHashSet()) { it.summary.sources }
        val categoryIds =
            flatMapTo(LinkedHashSet()) { it.summary.categoryIds }
                .toList()

        // Best (lowest) rank across all sources.
        val bestRank = minOf { it.rankInSource }
        val sourceCount = sources.size
        val score = bestRank.toDouble() - MULTI_SOURCE_BOOST_PER_EXTRA_SOURCE * (sourceCount - 1)

        // Display fields: richer is better (longest non-blank wins).
        val description = pickLongest { it.description }
        val artworkUrl = pickFirstNonBlank { it.artworkUrl }
        val category = pickFirstNonBlank { it.category }
        val episodeCount = maxOf { it.summary.episodeCount }

        val merged =
            primary.summary.copy(
                description = description,
                artworkUrl = artworkUrl,
                category = category,
                episodeCount = episodeCount,
                categoryIds = categoryIds,
                sources = sources,
            )
        return Collapsed(summary = merged, score = score, sourceCount = sourceCount)
    }

    private inline fun List<RankedResult>.pickLongest(selector: (PodcastSummary) -> String): String {
        var best = ""
        for (r in this) {
            val v = selector(r.summary)
            if (v.length > best.length) best = v
        }
        return best
    }

    private inline fun List<RankedResult>.pickFirstNonBlank(selector: (PodcastSummary) -> String): String {
        for (r in this) {
            val v = selector(r.summary)
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private inline fun List<RankedResult>.maxOf(selector: (RankedResult) -> Int): Int {
        var best = 0
        for (r in this) {
            val v = selector(r)
            if (v > best) best = v
        }
        return best
    }

    private inline fun List<RankedResult>.minOf(selector: (RankedResult) -> Int): Int {
        var best = Int.MAX_VALUE
        for (r in this) {
            val v = selector(r)
            if (v < best) best = v
        }
        return if (best == Int.MAX_VALUE) 0 else best
    }

    private sealed interface IdentityKey

    private data class TitleAuthor(val title: String, val author: String) : IdentityKey

    /** Sentinel that keeps a group with no usable identity in its own bucket. */
    private data class GroupSelf(val feedUrl: String) : IdentityKey

    private data class Collapsed(
        val summary: PodcastSummary,
        val score: Double,
        val sourceCount: Int,
    )
}

/**
 * A single source's response item, carrying its 0-based position in that source's
 * own result list. The position is what [SearchResultMerger] uses for ranking — the
 * sources never compare relevance scores directly because they're incomparable.
 */
data class RankedResult(
    val summary: PodcastSummary,
    val rankInSource: Int,
)

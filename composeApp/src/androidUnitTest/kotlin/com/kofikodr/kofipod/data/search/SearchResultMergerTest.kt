// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.search

import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.domain.SourceId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [SearchResultMerger]'s dedup + merge + rank contract.
 *
 * Each test asserts an observable outcome of `mergeAndRank` — never a private
 * implementation detail. The merger is pure, so all assertions read the returned
 * list shape and field values directly.
 */
class SearchResultMergerTest {
    @Test
    fun emptyInput_returnsEmpty() {
        assertEquals(emptyList(), SearchResultMerger.mergeAndRank(emptyList()))
    }

    @Test
    fun singleResultPassesThrough() {
        val s = piSummary(id = "100", feedUrl = "https://a.com/feed", title = "Show", author = "Host")
        val result = SearchResultMerger.mergeAndRank(listOf(RankedResult(s, rankInSource = 0)))
        assertEquals(1, result.size)
        assertEquals("Show", result.first().title)
        assertEquals(setOf(SourceId.PodcastIndex), result.first().sources)
    }

    @Test
    fun sameCanonicalFeedUrl_mergesAcrossSources() {
        val pi =
            piSummary(
                id = "100",
                feedUrl = "https://a.com/feed",
                title = "PI Title",
                description = "Short",
                artworkUrl = "https://a.com/art-pi.jpg",
                categoryIds = listOf(1, 2),
            )
        val itunes =
            itunesSummary(
                id = "itunes:777",
                // Cosmetically different — http, trailing slash, tracking param —
                // but the canonicaliser collapses both URLs to the same key.
                feedUrl = "http://a.com/feed/?utm_source=apple",
                title = "iTunes Title",
                description = "A much longer description from the iTunes side",
                artworkUrl = "https://a.com/art-itunes.jpg",
                categoryIds = listOf(3),
            )

        val merged =
            SearchResultMerger.mergeAndRank(
                listOf(
                    RankedResult(pi, rankInSource = 0),
                    RankedResult(itunes, rankInSource = 0),
                ),
            )

        assertEquals(1, merged.size, "Same canonical feed URL must dedup to one row")
        val row = merged.single()
        // Identity prefers PI for downstream feedId-based callers.
        assertEquals("100", row.id)
        assertEquals(100L, row.feedId)
        // Sources union.
        assertEquals(setOf(SourceId.PodcastIndex, SourceId.ITunes), row.sources)
        // Richer description wins.
        assertEquals("A much longer description from the iTunes side", row.description)
        // Category ids union (order preserved).
        assertEquals(listOf(1, 2, 3), row.categoryIds)
    }

    @Test
    fun titleAuthorFallback_mergesWhenUrlsDiffer() {
        // Two sources return the same podcast but with cosmetically-divergent URLs
        // the URL canonicaliser can't collapse (different hosts). Title+author
        // fallback catches it.
        val pi =
            piSummary(
                id = "200",
                feedUrl = "https://feeds.example.com/shows/abc",
                title = "Two Hosts Talk",
                author = "Alice & Bob",
            )
        val itunes =
            itunesSummary(
                id = "itunes:999",
                feedUrl = "https://cdn.alt.com/abc.xml",
                title = "Two Hosts Talk",
                author = "Alice & Bob",
            )

        val merged =
            SearchResultMerger.mergeAndRank(
                listOf(
                    RankedResult(pi, rankInSource = 1),
                    RankedResult(itunes, rankInSource = 0),
                ),
            )
        assertEquals(1, merged.size, "Same title+author must merge across diverging hosts")
        assertEquals(setOf(SourceId.PodcastIndex, SourceId.ITunes), merged.single().sources)
    }

    @Test
    fun differentPodcastsWithBlankIdentityFields_doNotMerge() {
        // Two distinct podcasts both with empty author or blank title must stay
        // separate even though their identity fallback key is "the same blank".
        val a = piSummary(id = "300", feedUrl = "https://x.com/a", title = "", author = "")
        val b = piSummary(id = "301", feedUrl = "https://y.com/b", title = "", author = "")

        val merged =
            SearchResultMerger.mergeAndRank(
                listOf(
                    RankedResult(a, rankInSource = 0),
                    RankedResult(b, rankInSource = 1),
                ),
            )
        assertEquals(2, merged.size, "Blank identity must not be a merge key")
    }

    @Test
    fun multiSourceBoost_outranksHigherSingleSourceResult() {
        // A multi-source result at rank 3 beats a single-source result at rank 2.
        // Boost = 0.5 per extra source → score(multi) = 3 - 0.5 = 2.5; score(single) = 2.0.
        // Lower score = higher rank, so the SINGLE source at rank 2 should win
        // outright, BUT a multi-source at rank 1 beats single at rank 1.
        val piA = piSummary(id = "1", feedUrl = "https://a.com/feed", title = "A", author = "X")
        val itunesA =
            itunesSummary(
                id = "itunes:1",
                feedUrl = "https://a.com/feed",
                title = "A",
                author = "X",
            )
        val piB = piSummary(id = "2", feedUrl = "https://b.com/feed", title = "B", author = "Y")

        val merged =
            SearchResultMerger.mergeAndRank(
                listOf(
                    RankedResult(piA, rankInSource = 1),
                    RankedResult(itunesA, rankInSource = 1),
                    RankedResult(piB, rankInSource = 0),
                ),
            )
        assertEquals(2, merged.size)
        // piB has score 0 (rank 0). The piA+itunesA merge has score 1 - 0.5 = 0.5.
        // piB wins.
        assertEquals("2", merged[0].id, "Single-source at rank 0 still beats multi-source at rank 1")
        assertEquals("1", merged[1].id)
    }

    @Test
    fun sameRankAcrossSources_multiSourceWins() {
        // When two podcasts return at the same single-source rank, the multi-source
        // one should outrank the single-source one thanks to the agreement boost.
        val piA = piSummary(id = "1", feedUrl = "https://a.com/feed", title = "A", author = "X")
        val itunesA =
            itunesSummary(
                id = "itunes:1",
                feedUrl = "https://a.com/feed",
                title = "A",
                author = "X",
            )
        val piB = piSummary(id = "2", feedUrl = "https://b.com/feed", title = "B", author = "Y")

        val merged =
            SearchResultMerger.mergeAndRank(
                listOf(
                    RankedResult(piA, rankInSource = 1),
                    RankedResult(itunesA, rankInSource = 2),
                    RankedResult(piB, rankInSource = 1),
                ),
            )
        // piB score = 1.0. piA+itunesA score = min(1,2) - 0.5 = 0.5. Multi wins.
        assertEquals(
            2,
            merged[0].sources.size,
            "Multi-source result should rank first when single-source counterpart is at same position",
        )
        assertEquals("1", merged[0].id)
    }

    @Test
    fun resultsPreserveInputOrderWhenScoresEqual() {
        // Two single-source results both at rank 0 → equal scores. Stable order
        // (the LinkedHashMap-backed dedup keeps insertion order) means the first
        // input comes first.
        val a = piSummary(id = "1", feedUrl = "https://a.com/feed", title = "A", author = "X")
        val b = piSummary(id = "2", feedUrl = "https://b.com/feed", title = "B", author = "Y")

        val merged =
            SearchResultMerger.mergeAndRank(
                listOf(
                    RankedResult(a, rankInSource = 0),
                    RankedResult(b, rankInSource = 0),
                ),
            )
        assertEquals(listOf("1", "2"), merged.map { it.id })
    }

    @Test
    fun episodeCount_takesMaxAcrossSources() {
        // The richer field-pick rule applies to episodeCount: keep the higher number.
        val pi =
            piSummary(
                id = "1",
                feedUrl = "https://a.com/feed",
                title = "Show",
                author = "Host",
                episodeCount = 5,
            )
        val itunes =
            itunesSummary(
                id = "itunes:1",
                feedUrl = "https://a.com/feed",
                title = "Show",
                author = "Host",
                episodeCount = 999,
            )

        val merged =
            SearchResultMerger.mergeAndRank(
                listOf(
                    RankedResult(pi, rankInSource = 0),
                    RankedResult(itunes, rankInSource = 0),
                ),
            )
        assertEquals(999, merged.single().episodeCount)
    }

    private fun piSummary(
        id: String,
        feedUrl: String,
        title: String,
        author: String = "Host",
        description: String = "",
        artworkUrl: String = "",
        categoryIds: List<Int> = emptyList(),
        episodeCount: Int = 0,
    ) = PodcastSummary(
        id = id,
        feedId = id.toLong(),
        title = title,
        author = author,
        description = description,
        artworkUrl = artworkUrl,
        feedUrl = feedUrl,
        category = "",
        episodeCount = episodeCount,
        categoryIds = categoryIds,
        sources = setOf(SourceId.PodcastIndex),
    )

    private fun itunesSummary(
        id: String,
        feedUrl: String,
        title: String,
        author: String = "Host",
        description: String = "",
        artworkUrl: String = "",
        categoryIds: List<Int> = emptyList(),
        episodeCount: Int = 0,
    ) = PodcastSummary(
        id = id,
        feedId = 0L,
        title = title,
        author = author,
        description = description,
        artworkUrl = artworkUrl,
        feedUrl = feedUrl,
        category = "",
        episodeCount = episodeCount,
        categoryIds = categoryIds,
        sources = setOf(SourceId.ITunes),
    )
}

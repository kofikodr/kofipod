// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.repo

import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.domain.SourceId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pins [AggregateSearchSource]'s fanout contract:
 *  - Sources run concurrently and their results merge into one ranked list.
 *  - A single source throwing must not fail the aggregate — the other source's
 *    results still come back.
 *  - A source exceeding the per-source timeout is treated as empty; peers still
 *    contribute.
 *  - When **every** source fails / times out, the aggregator throws so
 *    SearchViewModel.runSearch's onFailure can surface a real error instead of
 *    silently rendering "No results".
 *  - `CancellationException` from a source propagates (structured cancellation
 *    must not be swallowed).
 *  - `UnsupportedSearchModeException` from a source is skipped — it neither
 *    counts toward "succeeded" (which would mask a peer failure) nor toward
 *    "failed" (which would surface a misleading error when peers worked).
 *
 * Uses fake [SearchSource] impls — never mocks the SUT itself, which is the
 * aggregator. The fakes are pure stand-ins for "what a source returns" and are
 * the integration points being verified.
 */
class AggregateSearchSourceTest {
    @Test
    fun bothSourcesReturn_mergedAndRanked() =
        runTest {
            val piResults =
                listOf(
                    summary(id = "1", feedUrl = "https://a.com/feed", title = "A", author = "X"),
                    summary(id = "2", feedUrl = "https://b.com/feed", title = "B", author = "Y"),
                )
            val itunesResults =
                listOf(
                    summary(
                        id = "itunes:1",
                        feedUrl = "https://a.com/feed",
                        title = "A",
                        author = "X",
                        source = SourceId.ITunes,
                    ),
                    summary(
                        id = "itunes:9",
                        feedUrl = "https://c.com/feed",
                        title = "C",
                        author = "Z",
                        source = SourceId.ITunes,
                    ),
                )
            val aggregate =
                AggregateSearchSource(
                    sources = listOf(FakeSource(piResults), FakeSource(itunesResults)),
                )
            val result = aggregate.searchAll(query = "test", limit = 10)
            // Three distinct podcasts (a.com is shared and merges).
            assertEquals(3, result.size)
            val a = result.first { it.feedUrl == "https://a.com/feed" }
            assertEquals(
                setOf(SourceId.PodcastIndex, SourceId.ITunes),
                a.sources,
                "Shared URL must show both sources in the union",
            )
            // Ranking expectation:
            //  - a.com: PI rank 0 + iTunes rank 0 → score 0 - 0.5 = -0.5 (multi-source boost)
            //  - b.com: PI rank 1 only → score 1.0
            //  - c.com: iTunes rank 1 only → score 1.0
            // a.com must come first; b.com and c.com tie but the dedup preserves the
            // PI-vs-iTunes insertion order, so b.com (PI) precedes c.com (iTunes).
            assertEquals(
                listOf("https://a.com/feed", "https://b.com/feed", "https://c.com/feed"),
                result.map { it.feedUrl },
                "Multi-source agreement must outrank single-source results at equal positions",
            )
        }

    @Test
    fun oneSourceThrows_otherSourceResultsStillReturned() =
        runTest {
            val pi =
                listOf(
                    summary(id = "1", feedUrl = "https://a.com/feed", title = "A", author = "X"),
                )
            val aggregate =
                AggregateSearchSource(
                    sources =
                        listOf(
                            FakeSource(pi),
                            ThrowingSource(),
                        ),
                )
            val result = aggregate.searchAll(query = "x", limit = 10)
            assertEquals(1, result.size)
            assertEquals("1", result.single().id)
        }

    @Test
    fun bothSourcesThrow_propagatesAFailureSoViewModelCanSurfaceError() =
        runTest {
            val aggregate =
                AggregateSearchSource(
                    sources = listOf(ThrowingSource(), ThrowingSource()),
                )
            // Previously the aggregator returned emptyList() when all sources failed,
            // which the UI rendered as "No results" — masking a real outage. The
            // contract is now: if no source succeeded, surface a failure so
            // SearchViewModel.runSearch's onFailure path can populate state.error.
            val ex =
                assertFailsWith<IllegalStateException> {
                    aggregate.searchAll(query = "x", limit = 10)
                }
            assertEquals("boom", ex.message)
        }

    @Test
    fun allSourcesTimeOut_throwsTimeoutMessage() =
        runTest {
            val aggregate =
                AggregateSearchSource(
                    sources = listOf(SlowSource(delayMs = 1_000L), SlowSource(delayMs = 1_000L)),
                    perSourceTimeoutMs = 50L,
                )
            // Same reason as bothSourcesThrow: don't silently render "No results"
            // when the user's network just dropped — surface a regular throwable
            // (NOT a CancellationException — that would propagate as structured
            // cancellation and never reach SearchViewModel.runSearch's onFailure)
            // with a message NetworkErrorHandler routes verbatim to the UI.
            val ex =
                assertFailsWith<IllegalStateException> {
                    aggregate.searchAll(query = "x", limit = 10)
                }
            kotlin.test.assertTrue(
                ex.message?.contains("timed out", ignoreCase = true) == true,
                "Timeout message must mention timeout so the user knows the cause; was: ${ex.message}",
            )
        }

    @Test
    fun cancellationExceptionFromSource_propagatesNotSwallowed() =
        runTest {
            // Structured cancellation (a new keystroke arrived; collectLatest cancels
            // the in-flight searchJob) MUST propagate through the aggregator. The
            // earlier `runCatching` form would swallow this and let the stale search
            // complete, overwriting the new query's loading state. A source that
            // throws plain CancellationException is the unit-level analogue — the
            // aggregator must re-throw, not convert to Outcome.Failed.
            //
            // NB: TimeoutCancellationException is intentionally NOT treated this way —
            // it's the per-source budget firing, not structured cancellation. The
            // separate `slowSourceTimesOut_fastSourceStillReturns` test pins that.
            val cancellingSource =
                object : SearchSource {
                    override suspend fun searchAll(
                        query: String,
                        limit: Int,
                    ): List<com.kofikodr.kofipod.domain.PodcastSummary> = throw CancellationException("parent cancelled")

                    override suspend fun searchByTitle(
                        query: String,
                        limit: Int,
                    ): List<com.kofikodr.kofipod.domain.PodcastSummary> = throw CancellationException("parent cancelled")

                    override suspend fun searchByPerson(
                        name: String,
                        limit: Int,
                    ): List<com.kofikodr.kofipod.domain.PodcastSummary> = throw CancellationException("parent cancelled")
                }
            val aggregate = AggregateSearchSource(sources = listOf(cancellingSource))
            assertFailsWith<CancellationException> {
                aggregate.searchAll(query = "x", limit = 10)
            }
        }

    @Test
    fun slowSourceTimesOut_fastSourceStillReturns() =
        runTest {
            val pi =
                listOf(
                    summary(id = "1", feedUrl = "https://a.com/feed", title = "A", author = "X"),
                )
            val aggregate =
                AggregateSearchSource(
                    // 50ms timeout — tiny so the slow source is guaranteed to miss it.
                    sources = listOf(FakeSource(pi), SlowSource(delayMs = 1_000L)),
                    perSourceTimeoutMs = 50L,
                )
            val result = aggregate.searchAll(query = "x", limit = 10)
            assertEquals(1, result.size)
            assertEquals("1", result.single().id)
            // The result should be from PI only — no iTunes contribution.
            assertEquals(setOf(SourceId.PodcastIndex), result.single().sources)
        }

    @Test
    fun searchByPerson_piSucceedsItunesSkipped_returnsPiResults() =
        runTest {
            // ItunesSearchRepository.searchByPerson throws UnsupportedSearchModeException;
            // verify the aggregate still returns whatever Podcast Index produces (the
            // Skipped outcome doesn't pollute ranking or results).
            val piResults =
                listOf(
                    summary(id = "10", feedUrl = "https://a.com/feed", title = "A", author = "X"),
                    summary(id = "11", feedUrl = "https://b.com/feed", title = "B", author = "Y"),
                )
            val aggregate =
                AggregateSearchSource(
                    sources = listOf(FakeSource(piResults), UnsupportedPersonSearchSource()),
                )
            val result = aggregate.searchByPerson(name = "host", limit = 10)
            assertEquals(2, result.size)
            assertEquals(
                setOf("10", "11"),
                result.map { it.id }.toSet(),
                "Skipped iTunes must not interfere with PI's person-search results",
            )
        }

    @Test
    fun searchByPerson_piFailsItunesSkipped_propagatesPiError() =
        runTest {
            // The critical masking scenario the kode-review caught: previously, iTunes
            // returning emptyList() for person search was Outcome.Ok(emptyList), so when
            // PI failed the aggregator saw "one source succeeded" and silently rendered
            // "No results". With UnsupportedSearchModeException → Outcome.Skipped, PI's
            // failure must propagate so SearchViewModel surfaces a real error message.
            val aggregate =
                AggregateSearchSource(
                    sources = listOf(ThrowingSource(), UnsupportedPersonSearchSource()),
                )
            val ex =
                assertFailsWith<IllegalStateException> {
                    aggregate.searchByPerson(name = "host", limit = 10)
                }
            assertEquals("boom", ex.message)
        }

    @Test
    fun limitIsAppliedAfterMerge() =
        runTest {
            // 5 PI results + 5 iTunes results (all distinct) → 10 raw. limit=3 → 3.
            val pi = (0 until 5).map { i -> summary(id = "$i", feedUrl = "https://pi.com/$i", title = "P$i") }
            val itunes =
                (0 until 5).map { i ->
                    summary(
                        id = "itunes:$i",
                        feedUrl = "https://it.com/$i",
                        title = "I$i",
                        source = SourceId.ITunes,
                    )
                }
            val aggregate =
                AggregateSearchSource(sources = listOf(FakeSource(pi), FakeSource(itunes)))
            val result = aggregate.searchAll(query = "q", limit = 3)
            assertEquals(3, result.size, "limit must cap the merged list")
        }

    private fun summary(
        id: String,
        feedUrl: String,
        title: String,
        author: String = "Host",
        source: SourceId = SourceId.PodcastIndex,
    ) = PodcastSummary(
        id = id,
        feedId = id.toLongOrNull() ?: 0L,
        title = title,
        author = author,
        description = "",
        artworkUrl = "",
        feedUrl = feedUrl,
        sources = setOf(source),
    )

    private class FakeSource(private val results: List<PodcastSummary>) : SearchSource {
        override suspend fun searchAll(
            query: String,
            limit: Int,
        ): List<PodcastSummary> = results

        override suspend fun searchByTitle(
            query: String,
            limit: Int,
        ): List<PodcastSummary> = results

        override suspend fun searchByPerson(
            name: String,
            limit: Int,
        ): List<PodcastSummary> = results
    }

    /** Mimics [com.kofikodr.kofipod.data.repo.ItunesSearchRepository] which throws
     *  `UnsupportedSearchModeException` for person searches but otherwise returns empty. */
    private class UnsupportedPersonSearchSource : SearchSource {
        override suspend fun searchAll(
            query: String,
            limit: Int,
        ) = emptyList<PodcastSummary>()

        override suspend fun searchByTitle(
            query: String,
            limit: Int,
        ) = emptyList<PodcastSummary>()

        override suspend fun searchByPerson(
            name: String,
            limit: Int,
        ): List<PodcastSummary> = throw UnsupportedSearchModeException("not supported")
    }

    private class ThrowingSource : SearchSource {
        override suspend fun searchAll(
            query: String,
            limit: Int,
        ): List<PodcastSummary> = error("boom")

        override suspend fun searchByTitle(
            query: String,
            limit: Int,
        ): List<PodcastSummary> = error("boom")

        override suspend fun searchByPerson(
            name: String,
            limit: Int,
        ): List<PodcastSummary> = error("boom")
    }

    private class SlowSource(private val delayMs: Long) : SearchSource {
        override suspend fun searchAll(
            query: String,
            limit: Int,
        ): List<PodcastSummary> {
            delay(delayMs)
            return emptyList()
        }

        override suspend fun searchByTitle(
            query: String,
            limit: Int,
        ): List<PodcastSummary> {
            delay(delayMs)
            return emptyList()
        }

        override suspend fun searchByPerson(
            name: String,
            limit: Int,
        ): List<PodcastSummary> {
            delay(delayMs)
            return emptyList()
        }
    }
}

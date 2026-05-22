// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.repo

import com.kofikodr.kofipod.data.search.RankedResult
import com.kofikodr.kofipod.data.search.SearchResultMerger
import com.kofikodr.kofipod.domain.PodcastSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout

/**
 * Fans a single search query out to multiple [SearchSource]s concurrently and merges
 * their responses into one ranked list.
 *
 *  - Each source runs in its own `async` under a shared [coroutineScope], so structured
 *    cancellation (a new keystroke arrived in SearchViewModel's `collectLatest`)
 *    propagates to every in-flight source cleanly. Tolerance is **partial**: a source
 *    that throws or times out is treated as having contributed no results — one
 *    source's 5xx must not break the whole search.
 *  - But when **every** source fails or times out, the aggregator re-throws so
 *    [com.kofikodr.kofipod.ui.screens.search.SearchViewModel]'s `runSearch().onFailure`
 *    can surface a real error message. Returning an empty list in that case would
 *    silently mask a total outage as "No results".
 *  - Each source is bounded by [perSourceTimeoutMs]; a slow source can't drag the
 *    whole search past its budget.
 *
 * Sources are passed in priority order. For ranking ties, an earlier source's result
 * wins — keep Podcast Index first when constructing the list in Koin so the legacy
 * behaviour (PI's relevance ordering when iTunes contributes nothing) is preserved.
 */
class AggregateSearchSource(
    private val sources: List<SearchSource>,
    private val perSourceTimeoutMs: Long = DEFAULT_PER_SOURCE_TIMEOUT_MS,
) : SearchSource {
    override suspend fun searchAll(
        query: String,
        limit: Int,
    ): List<PodcastSummary> = fanout(limit) { it.searchAll(query, limit) }

    override suspend fun searchByTitle(
        query: String,
        limit: Int,
    ): List<PodcastSummary> = fanout(limit) { it.searchByTitle(query, limit) }

    override suspend fun searchByPerson(
        name: String,
        limit: Int,
    ): List<PodcastSummary> = fanout(limit) { it.searchByPerson(name, limit) }

    private suspend fun fanout(
        limit: Int,
        call: suspend (SearchSource) -> List<PodcastSummary>,
    ): List<PodcastSummary> =
        coroutineScope {
            val outcomes =
                sources.map { src ->
                    async {
                        try {
                            Outcome.Ok(withTimeout(perSourceTimeoutMs) { call(src) })
                        } catch (e: CancellationException) {
                            // CancellationException MUST propagate so structured cancellation
                            // (e.g. SearchViewModel's collectLatest cancelling a stale search)
                            // works. Only a plain `runCatching` would silently swallow it.
                            // A TimeoutCancellationException isn't a structured cancellation —
                            // it's the per-source budget firing — so route it to Outcome.TimedOut.
                            if (e is TimeoutCancellationException) {
                                Outcome.TimedOut
                            } else {
                                throw e
                            }
                        } catch (e: UnsupportedSearchModeException) {
                            // A source declaring it can't handle this query mode (e.g. iTunes
                            // person search) is NOT a failure and NOT a successful empty —
                            // either would mask a peer source's real error. Skip it entirely
                            // when judging whether the aggregate succeeded.
                            Outcome.Skipped
                        } catch (e: Throwable) {
                            Outcome.Failed(e)
                        }
                    }
                }.awaitAll()

            // If every source threw / timed out, surface a failure so SearchViewModel's
            // onFailure path can show a real error message instead of silently rendering
            // "No results" — the previous single-source code propagated thrown errors,
            // and that contract has to hold through the aggregator.
            val anySucceeded = outcomes.any { it is Outcome.Ok }
            if (!anySucceeded) {
                val firstFailure =
                    outcomes.firstNotNullOfOrNull { (it as? Outcome.Failed)?.error }
                if (firstFailure != null) throw firstFailure
                if (outcomes.any { it is Outcome.TimedOut }) {
                    // TimeoutCancellationException's constructor is internal in
                    // kotlinx.coroutines, so we throw a plain Throwable that
                    // NetworkErrorHandler.handle routes through its `Other` branch,
                    // returning the message verbatim to the UI.
                    error("Search timed out — please check your connection and try again")
                }
            }

            // Rank position is per source — preserve each Ok bucket's local index
            // (matching the "0-based position in that source's own result list" contract).
            val ranked =
                outcomes
                    .filterIsInstance<Outcome.Ok>()
                    .flatMap { ok ->
                        ok.results.mapIndexed { index, summary ->
                            RankedResult(summary = summary, rankInSource = index)
                        }
                    }

            SearchResultMerger.mergeAndRank(ranked).take(limit)
        }

    private sealed interface Outcome {
        data class Ok(val results: List<PodcastSummary>) : Outcome

        data object TimedOut : Outcome

        /** Source declared it can't handle this query mode — neither success nor failure. */
        data object Skipped : Outcome

        data class Failed(val error: Throwable) : Outcome
    }

    companion object {
        /**
         * Per-source budget. Set generously (10s) to accommodate Podcast Index's
         * `searchByPerson`, which is internally a two-stage call (episode search →
         * per-feedId podcast lookup) and can stretch past several seconds on a
         * marginal connection. A tighter bound surfaced a live timeout on the
         * Person tab during emulator verification. Matches the shared HttpClient's
         * own 15s request timeout — the aggregator's job is cancellation discipline,
         * not premature impatience.
         */
        const val DEFAULT_PER_SOURCE_TIMEOUT_MS: Long = 10_000L
    }
}

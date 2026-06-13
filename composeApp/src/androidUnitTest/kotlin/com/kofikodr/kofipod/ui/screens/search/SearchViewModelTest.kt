// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.search

import com.kofikodr.kofipod.data.net.NetworkErrorHandler
import com.kofikodr.kofipod.data.recommend.RecommendationsRepository
import com.kofikodr.kofipod.data.recommend.RecommendationsSource
import com.kofikodr.kofipod.data.recommend.RecommendationsState
import com.kofikodr.kofipod.data.recommend.ReshuffleResult
import com.kofikodr.kofipod.data.repo.CategoriesSource
import com.kofikodr.kofipod.data.repo.SearchSource
import com.kofikodr.kofipod.diagnostics.NoOpTelemetry
import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.opml.PodcastFeedLookup
import com.mr3y.podcastindex.model.Category
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @Test
    fun `stale load more result cannot overwrite a newer query result`() =
        runVmTest {
            val search = BlockingSearchSource()
            val vm = newViewModel(search)

            vm.setQuery("alpha")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
            advanceUntilIdle()
            assertEquals((1..10).map { "alpha-$it" }, vm.state.value.resultIds())

            vm.loadMore()
            search.awaitAlphaLoadMoreStarted()

            vm.setQuery("beta")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
            advanceUntilIdle()
            assertEquals(listOf("beta-1"), vm.state.value.resultIds())

            search.releaseAlphaLoadMore()
            advanceUntilIdle()

            assertEquals("beta", vm.state.value.query)
            assertEquals(listOf("beta-1"), vm.state.value.resultIds())
        }

    @Test
    fun `stale load more result cannot overwrite a restarted same-query search`() =
        runVmTest {
            val search = BlockingSearchSource()
            val vm = newViewModel(search)

            vm.setQuery("alpha")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
            advanceUntilIdle()
            assertEquals((1..10).map { "alpha-$it" }, vm.state.value.resultIds())

            vm.loadMore()
            search.awaitAlphaLoadMoreStarted()

            vm.setQuery("beta")
            vm.setQuery("alpha")
            advanceTimeBy(SearchViewModel.DEBOUNCE_MS)
            advanceUntilIdle()
            assertEquals((1..10).map { "alpha-$it" }, vm.state.value.resultIds())

            search.releaseAlphaLoadMore()
            advanceUntilIdle()

            assertEquals("alpha", vm.state.value.query)
            assertEquals((1..10).map { "alpha-$it" }, vm.state.value.resultIds())
        }

    private fun runVmTest(block: suspend TestScope.() -> Unit) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            try {
                block()
            } finally {
                Dispatchers.resetMain()
            }
        }

    private fun TestScope.newViewModel(search: SearchSource): SearchViewModel =
        SearchViewModel(
            repo = search,
            categories = EmptyCategories,
            recommendations = NoopRecommendations,
            appScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
            errors = NetworkErrorHandler(),
            telemetry = NoOpTelemetry,
            feedLookup = PodcastFeedLookup { error("Feed lookup is not used by search load-more tests") },
        )

    private fun SearchUiState.resultIds(): List<String> = results.map { it.id }

    private object EmptyCategories : CategoriesSource {
        override fun popular(): List<Category> = emptyList()
    }

    private object NoopRecommendations : RecommendationsSource {
        private val state =
            MutableStateFlow(
                RecommendationsState(
                    items = emptyList(),
                    reshufflesRemaining = RecommendationsRepository.MAX_DAILY_RESHUFFLES,
                ),
            )

        override fun observe(): Flow<RecommendationsState> = state

        override suspend fun refreshIfStale() = Unit

        override suspend fun reshuffle(): ReshuffleResult = ReshuffleResult.NoData
    }

    private class BlockingSearchSource : SearchSource {
        private val alphaLoadMoreStarted = CompletableDeferred<Unit>()
        private val alphaLoadMoreRelease = CompletableDeferred<Unit>()

        override suspend fun searchAll(
            query: String,
            limit: Int,
        ): List<PodcastSummary> {
            if (query == "alpha" && limit > 10) {
                withContext(NonCancellable) {
                    alphaLoadMoreStarted.complete(Unit)
                    alphaLoadMoreRelease.await()
                }
            }
            return resultsFor(query = query, limit = limit)
        }

        override suspend fun searchByTitle(
            query: String,
            limit: Int,
        ): List<PodcastSummary> = resultsFor(query = query, limit = limit)

        override suspend fun searchByPerson(
            name: String,
            limit: Int,
        ): List<PodcastSummary> = resultsFor(query = name, limit = limit)

        suspend fun awaitAlphaLoadMoreStarted() {
            alphaLoadMoreStarted.await()
        }

        fun releaseAlphaLoadMore() {
            alphaLoadMoreRelease.complete(Unit)
        }
    }
}

private fun resultsFor(
    query: String,
    limit: Int,
): List<PodcastSummary> {
    val count = if (query == "beta") 1 else limit
    return (1..count).map { index ->
        PodcastSummary(
            id = "$query-$index",
            feedId = index.toLong(),
            title = "$query result $index",
            author = "Author $index",
            description = "Description $index",
            artworkUrl = "https://example.com/$query/$index.jpg",
            feedUrl = "https://example.com/$query/$index.xml",
        )
    }
}

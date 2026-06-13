// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.library

import com.kofikodr.kofipod.data.net.NetworkErrorHandler
import com.kofikodr.kofipod.data.repo.EpisodeSource
import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.data.repo.RecentlyViewedRepository
import com.kofikodr.kofipod.data.repo.RefreshResult
import com.kofikodr.kofipod.data.repo.SearchSource
import com.kofikodr.kofipod.db.Episode
import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.opml.PodcastFeedLookup
import com.kofikodr.kofipod.testing.inMemoryDatabaseWithDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryDetailViewModelTest {
    @Test
    fun `saving iTunes-only search result hydrates before inserting into library`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val (db, driver) = inMemoryDatabaseWithDriver()
            try {
                val library = LibraryRepository(db, queryDispatcher = UnconfinedTestDispatcher(testScheduler))
                val lookup =
                    FakePodcastFeedLookup(
                        "https://itunes.example/show.xml" to podcastSummary(id = "456", feedId = 456L),
                    )
                val vm = newViewModel(library = library, recentlyViewed = RecentlyViewedRepository(db), lookup = lookup)

                vm.addSummaryToList(
                    podcastSummary(
                        id = "itunes:123",
                        feedId = 0L,
                        feedUrl = "https://itunes.example/show.xml",
                    ),
                )
                advanceUntilIdle()

                assertEquals(listOf("https://itunes.example/show.xml"), lookup.urls)
                assertNull(library.podcastNow("itunes:123"), "sentinel ids must never be persisted")
                val saved = library.podcastNow("456")
                assertNotNull(saved)
                assertEquals("Resolved Show", saved.title)
                assertEquals("https://resolved.example/feed.xml", saved.feedUrl)
            } finally {
                driver.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `saving iTunes-only search result without feed url refuses to insert sentinel id`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val (db, driver) = inMemoryDatabaseWithDriver()
            var stateCollector: Job? = null
            try {
                val library = LibraryRepository(db, queryDispatcher = UnconfinedTestDispatcher(testScheduler))
                val lookup = FakePodcastFeedLookup()
                val vm = newViewModel(library = library, recentlyViewed = RecentlyViewedRepository(db), lookup = lookup)
                stateCollector = launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }

                vm.addSummaryToList(podcastSummary(id = "itunes:123", feedId = 0L, feedUrl = ""))
                advanceUntilIdle()

                assertEquals(emptyList(), lookup.urls)
                assertNull(library.podcastNow("itunes:123"), "unhydratable iTunes ids must not be saved")
                assertEquals("This feed can't be saved because it has no feed URL.", vm.state.value.searchError)
            } finally {
                stateCollector?.cancel()
                advanceTimeBy(5_001L)
                advanceUntilIdle()
                driver.close()
                Dispatchers.resetMain()
            }
        }

    @Test
    fun `stale failed iTunes hydration cannot overwrite later successful save state`() =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            val (db, driver) = inMemoryDatabaseWithDriver()
            var stateCollector: Job? = null
            try {
                val library = LibraryRepository(db, queryDispatcher = UnconfinedTestDispatcher(testScheduler))
                val lookup =
                    BlockingFirstLookup(
                        laterUrl = "https://itunes.example/later.xml",
                        laterSummary = podcastSummary(id = "789", feedId = 789L),
                    )
                val vm = newViewModel(library = library, recentlyViewed = RecentlyViewedRepository(db), lookup = lookup)
                stateCollector = launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { } }

                vm.addSummaryToList(
                    podcastSummary(
                        id = "itunes:first",
                        feedId = 0L,
                        feedUrl = "https://itunes.example/first.xml",
                    ),
                )
                lookup.awaitFirstLookupStarted()

                vm.addSummaryToList(
                    podcastSummary(
                        id = "itunes:later",
                        feedId = 0L,
                        feedUrl = "https://itunes.example/later.xml",
                    ),
                )
                advanceUntilIdle()

                lookup.releaseFirstLookup()
                advanceUntilIdle()

                assertNull(library.podcastNow("itunes:first"), "the stale sentinel id must not be saved")
                assertNull(library.podcastNow("itunes:later"), "the later sentinel id must not be saved")
                assertNotNull(library.podcastNow("789"), "the later hydrated Podcast Index id should be saved")
                assertNull(vm.state.value.searchError, "stale failed hydration must not surface after a later success")
            } finally {
                stateCollector?.cancel()
                advanceTimeBy(5_001L)
                advanceUntilIdle()
                driver.close()
                Dispatchers.resetMain()
            }
        }

    private fun newViewModel(
        library: LibraryRepository,
        recentlyViewed: RecentlyViewedRepository,
        lookup: PodcastFeedLookup,
    ): LibraryDetailViewModel =
        LibraryDetailViewModel(
            listId = null,
            repo = library,
            search = NoopSearchSource,
            recentlyViewed = recentlyViewed,
            episodes = NoopEpisodeSource,
            errors = NetworkErrorHandler(),
            feedLookup = lookup,
        )

    private class FakePodcastFeedLookup(
        vararg pairs: Pair<String, PodcastSummary>,
    ) : PodcastFeedLookup {
        private val byUrl = pairs.toMap()
        val urls = mutableListOf<String>()

        override suspend fun resolve(feedUrl: String): PodcastSummary {
            urls += feedUrl
            return byUrl[feedUrl] ?: error("Unexpected feed lookup: $feedUrl")
        }
    }

    private class BlockingFirstLookup(
        private val laterUrl: String,
        private val laterSummary: PodcastSummary,
    ) : PodcastFeedLookup {
        private val firstStarted = CompletableDeferred<Unit>()
        private val releaseFirst = CompletableDeferred<Unit>()

        override suspend fun resolve(feedUrl: String): PodcastSummary {
            if (feedUrl == laterUrl) return laterSummary
            return withContext(NonCancellable) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                error("stale lookup failed")
            }
        }

        suspend fun awaitFirstLookupStarted() {
            firstStarted.await()
        }

        fun releaseFirstLookup() {
            releaseFirst.complete(Unit)
        }
    }

    private object NoopSearchSource : SearchSource {
        override suspend fun searchAll(
            query: String,
            limit: Int,
        ): List<PodcastSummary> = emptyList()

        override suspend fun searchByTitle(
            query: String,
            limit: Int,
        ): List<PodcastSummary> = emptyList()

        override suspend fun searchByPerson(
            name: String,
            limit: Int,
        ): List<PodcastSummary> = emptyList()
    }

    private object NoopEpisodeSource : EpisodeSource {
        override fun episodesFlow(podcastId: String): Flow<List<Episode>> = flowOf(emptyList())

        override fun episodeFlow(episodeId: String): Flow<Episode?> = flowOf(null)

        override fun newEpisodeCountsFlow(): Flow<Map<String, Int>> = flowOf(emptyMap())

        override suspend fun refresh(
            podcastId: String,
            feedId: Long,
            nowMillis: Long,
        ): RefreshResult = RefreshResult(insertedEpisodes = emptyList(), totalRemote = 0)
    }
}

private fun podcastSummary(
    id: String,
    feedId: Long,
    feedUrl: String = "https://resolved.example/feed.xml",
): PodcastSummary =
    PodcastSummary(
        id = id,
        feedId = feedId,
        title = "Resolved Show",
        author = "Resolved Author",
        description = "Resolved Description",
        artworkUrl = "https://resolved.example/art.jpg",
        feedUrl = feedUrl,
        category = "Technology",
    )

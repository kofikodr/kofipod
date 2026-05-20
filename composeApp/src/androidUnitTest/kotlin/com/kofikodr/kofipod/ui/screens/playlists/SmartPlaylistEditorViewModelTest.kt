// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.db.KofipodDatabase
import com.kofikodr.kofipod.playlists.EpisodeFacts
import com.kofikodr.kofipod.playlists.EpisodeFactsRepository
import com.kofikodr.kofipod.playlists.PlayState
import com.kofikodr.kofipod.playlists.PredicateEvaluator
import com.kofikodr.kofipod.playlists.SmartPlaylist
import com.kofikodr.kofipod.playlists.SmartPlaylistPredicate
import com.kofikodr.kofipod.playlists.SmartPlaylistRepository
import com.kofikodr.kofipod.playlists.SmartPlaylistResolver
import com.kofikodr.kofipod.testing.inMemoryDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SmartPlaylistEditorViewModel].
 *
 * The fakes here are intentionally minimal:
 *   - [FakeSmartPlaylistRepository] keeps an in-memory map of saved playlists, exposes
 *     them via a `MutableStateFlow`, and lets tests assert on the captured argument.
 *   - [FakeEpisodeFactsRepository] is backed by a `MutableStateFlow<List<EpisodeFacts>>`
 *     so each test can seed a deterministic set of facts. The real [PredicateEvaluator]
 *     does the actual filtering — we deliberately do NOT mock it (it's a pure-Kotlin
 *     deterministic class; mocking would lose coverage of the integration boundary).
 *   - [LibraryRepository] is constructed against [inMemoryDatabase] so the editor's
 *     `availablePodcasts` lookup returns a real list without us having to fake the
 *     concrete class.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartPlaylistEditorViewModelTest {
    private val fixedNow = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val fixedClock =
        object : Clock {
            override fun now(): Instant = fixedNow
        }

    private val viewModelStore = ViewModelStore()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private var testDispatcher: CoroutineDispatcher? = null
    private var testAppScope: kotlinx.coroutines.CoroutineScope? = null

    /**
     * Routes `Dispatchers.Main` to the test scheduler so `viewModelScope.launch { ... }`
     * (which defaults to `Main.immediate`) is drained by `advanceUntilIdle()`. Also
     * stashes the same dispatcher in [testDispatcher] so the [harness] can pass it into
     * the VM's `defaultDispatcher` and the [LibraryRepository] `queryDispatcher` —
     * routing every off-Main hop onto the test scheduler instead of the real
     * `Dispatchers.Default` pool, which otherwise races assertions and turns the class
     * flaky when run end-to-end. The `testAppScope` mirrors the production
     * named-"appScope" Koin binding so the VM's `delete()` launch is observable
     * from `advanceUntilIdle()`.
     *
     * After `block()` completes we clear the [viewModelStore] **inside** the same
     * `runTest` so each VM's `viewModelScope` is cancelled while its test scheduler is
     * still alive. Without this, an Eagerly-started `stateIn` collector survives the
     * test and leaks across to the next one.
     */
    private fun runVmTest(block: suspend TestScope.() -> Unit) =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            testDispatcher = dispatcher
            val appScope = kotlinx.coroutines.CoroutineScope(dispatcher)
            testAppScope = appScope
            Dispatchers.setMain(dispatcher)
            try {
                block()
            } finally {
                viewModelStore.clear()
                appScope.cancel()
                testAppScope = null
                testDispatcher = null
            }
        }

    private fun fact(
        episodeId: String,
        episodeTitle: String,
        podcastId: String = "p1",
        playState: PlayState = PlayState.Unplayed,
        durationSec: Int = 1_800,
        publishedAtMs: Long = fixedNow.toEpochMilliseconds() - 86_400_000L,
        transcriptUrl: String? = null,
        hasCachedTranscript: Boolean = false,
        hasSnippets: Boolean = false,
        isDownloaded: Boolean = false,
    ) = EpisodeFacts(
        episodeId = episodeId,
        episodeTitle = episodeTitle,
        podcastId = podcastId,
        publishedAtMs = publishedAtMs,
        durationSec = durationSec,
        transcriptUrl = transcriptUrl,
        hasCachedTranscript = hasCachedTranscript,
        hasSnippets = hasSnippets,
        isDownloaded = isDownloaded,
        playState = playState,
    )

    private fun seedPodcasts(
        db: KofipodDatabase,
        vararg podcasts: Pair<String, String>,
    ) {
        podcasts.forEach { (id, title) ->
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
                addedAt = 0,
                primaryCategory = "",
            )
        }
    }

    private data class Harness(
        val vm: SmartPlaylistEditorViewModel,
        val playlists: FakeSmartPlaylistRepository,
        val facts: FakeEpisodeFactsRepository,
    )

    private fun harness(
        playlistId: String? = null,
        seedPlaylists: List<SmartPlaylist> = emptyList(),
        seedFacts: List<EpisodeFacts> = emptyList(),
        availablePodcasts: List<Pair<String, String>> = listOf("p1" to "Show A", "p2" to "Show B"),
    ): Harness {
        val db = inMemoryDatabase()
        seedPodcasts(db, *availablePodcasts.toTypedArray())
        val library =
            LibraryRepository(
                db = db,
                queryDispatcher =
                    requireNotNull(testDispatcher) {
                        "harness() must be called inside runVmTest { ... }"
                    },
            )
        val playlists = FakeSmartPlaylistRepository().apply { seedPlaylists.forEach { saveSync(it) } }
        val facts = FakeEpisodeFactsRepository(seedFacts)
        val resolver = SmartPlaylistResolver(facts = facts, evaluator = PredicateEvaluator(), clock = fixedClock)
        val factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    SmartPlaylistEditorViewModel(
                        playlists = playlists,
                        resolver = resolver,
                        library = library,
                        playlistId = playlistId,
                        appScope =
                            requireNotNull(testAppScope) {
                                "harness() must be called inside runVmTest { ... }"
                            },
                        clock = fixedClock,
                        defaultDispatcher =
                            requireNotNull(testDispatcher) {
                                "harness() must be called inside runVmTest { ... }"
                            },
                    ) as T
            }
        // Hosting the VM in a ViewModelStore lets `tearDown` cancel its viewModelScope
        // via `store.clear()` between tests; without it Eagerly-started state collectors
        // leak across tests on the dead test scheduler. A unique key per call lets a
        // single test create multiple VMs without colliding on the cached instance.
        val key = "vm-${vmCounter++}"
        val vm = ViewModelProvider(viewModelStore, factory)[key, SmartPlaylistEditorViewModel::class.java]
        return Harness(vm, playlists, facts)
    }

    private var vmCounter = 0

    @Test
    fun createMode_initialState_hasEmptyDraftAndAllFactsMatched() =
        runVmTest {
            val seedFacts = listOf(fact("e1", "Ep 1"), fact("e2", "Ep 2"), fact("e3", "Ep 3"))
            val h = harness(seedFacts = seedFacts)
            advanceUntilIdle()

            val s = h.vm.state.value
            assertEquals("", s.name)
            assertEquals(SmartPlaylistPredicate.EMPTY, s.predicate)
            assertEquals(3, s.matchedCount, "EMPTY predicate must match every fact")
            assertFalse(s.isEditMode, "Create mode must clear isEditMode")
            assertNull(s.saveError)
        }

    @Test
    fun setName_updatesNameField() =
        runVmTest {
            val h = harness()
            advanceUntilIdle()

            h.vm.setName("Walks")
            advanceUntilIdle()

            assertEquals("Walks", h.vm.state.value.name)
        }

    @Test
    fun toggleState_updatesPredicateAndRefiltersMatchedCount() =
        runVmTest {
            val seedFacts =
                listOf(
                    fact("e1", "Played", playState = PlayState.Completed),
                    fact("e2", "Unplayed-1", playState = PlayState.Unplayed),
                    fact("e3", "Unplayed-2", playState = PlayState.Unplayed),
                )
            val h = harness(seedFacts = seedFacts)
            advanceUntilIdle()
            assertEquals(3, h.vm.state.value.matchedCount, "Sanity: EMPTY predicate matches all")

            h.vm.toggleState(PlayState.Unplayed)
            advanceUntilIdle()

            val s = h.vm.state.value
            assertEquals(PlayState.Unplayed, s.predicate.state, "Predicate must reflect the toggle")
            assertEquals(2, s.matchedCount, "Matched count must drop to the unplayed subset")
        }

    @Test
    fun togglePodcast_addsRemovesAndAddsAgain() =
        runVmTest {
            val h = harness()
            advanceUntilIdle()

            h.vm.togglePodcast("p1")
            advanceUntilIdle()
            assertEquals(setOf("p1"), h.vm.state.value.predicate.podcastIds)

            h.vm.togglePodcast("p1")
            advanceUntilIdle()
            assertNull(
                h.vm.state.value.predicate.podcastIds,
                "Removing the last id should clear the set entirely (null = no filter)",
            )

            h.vm.togglePodcast("p2")
            advanceUntilIdle()
            assertEquals(setOf("p2"), h.vm.state.value.predicate.podcastIds)
        }

    @Test
    fun save_blankName_returnsFalseAndWritesNothing() =
        runVmTest {
            val h = harness()
            advanceUntilIdle()

            val result = h.vm.save()
            advanceUntilIdle()

            assertFalse(result, "save() must return false for blank name")
            assertEquals(0, h.playlists.savedItems.size, "Repository must not be invoked on validation failure")
            assertEquals("Name is required", h.vm.state.value.saveError)
        }

    @Test
    fun save_validDraft_persistsCurrentNamePredicateAndClockTimestamp() =
        runVmTest {
            val h = harness()
            advanceUntilIdle()
            h.vm.setName("Recent Unplayed")
            h.vm.toggleState(PlayState.Unplayed)
            h.vm.setMaxAgeDays(7)
            advanceUntilIdle()

            val result = h.vm.save()
            advanceUntilIdle()

            assertTrue(result, "save() must return true on success")
            assertEquals(1, h.playlists.savedItems.size, "Exactly one save() call must have been recorded")
            val saved = h.playlists.savedItems.single()
            assertEquals("Recent Unplayed", saved.name)
            assertEquals(PlayState.Unplayed, saved.predicate.state)
            assertEquals(7, saved.predicate.maxAgeDays)
            assertEquals(
                fixedNow.toEpochMilliseconds(),
                saved.createdAtMs,
                "createdAtMs must come from the injected Clock",
            )
            assertTrue(saved.id.isNotBlank(), "Slugified id must be non-blank")
        }

    @Test
    fun editMode_prefillsNameAndPredicateFromRepository() =
        runVmTest {
            val seeded =
                SmartPlaylist(
                    id = "x",
                    name = "Long Reads",
                    predicate = SmartPlaylistPredicate(maxAgeDays = 30, hasTranscript = true),
                    createdAtMs = 999L,
                )
            val h = harness(playlistId = "x", seedPlaylists = listOf(seeded))
            advanceUntilIdle()

            val s = h.vm.state.value
            assertEquals("Long Reads", s.name)
            assertEquals(30, s.predicate.maxAgeDays)
            assertEquals(true, s.predicate.hasTranscript)
            assertTrue(s.isEditMode, "Edit mode must set isEditMode=true")
        }

    @Test
    fun delete_inEditMode_callsRepositoryDeleteWithPlaylistId() =
        runVmTest {
            val seeded =
                SmartPlaylist(
                    id = "x",
                    name = "Disposable",
                    predicate = SmartPlaylistPredicate.EMPTY,
                    createdAtMs = 1L,
                )
            val h = harness(playlistId = "x", seedPlaylists = listOf(seeded))
            advanceUntilIdle()

            h.vm.delete()
            advanceUntilIdle()

            assertEquals(listOf("x"), h.playlists.deletedIds, "delete() must forward the playlistId")
        }
}

/**
 * In-memory fake for [SmartPlaylistRepository]. Backed by a `MutableStateFlow<Map>` so
 * `observe`/`observeAll` re-emit on every save. Captures save/delete invocations for
 * assertions (`savedItems`, `deletedIds`).
 */
private class FakeSmartPlaylistRepository : SmartPlaylistRepository {
    private val store = MutableStateFlow<Map<String, SmartPlaylist>>(emptyMap())
    val savedItems: MutableList<SmartPlaylist> = mutableListOf()
    val deletedIds: MutableList<String> = mutableListOf()

    fun saveSync(playlist: SmartPlaylist) {
        store.update { it + (playlist.id to playlist) }
    }

    override fun observeAll(): Flow<List<SmartPlaylist>> = store.asStateFlow().map { it.values.sortedBy { p -> p.createdAtMs } }

    override fun observe(id: String): Flow<SmartPlaylist?> = store.asStateFlow().map { it[id] }

    override suspend fun save(playlist: SmartPlaylist) {
        savedItems += playlist
        store.update { it + (playlist.id to playlist) }
    }

    override suspend fun delete(id: String) {
        deletedIds += id
        store.update { it - id }
    }
}

/** In-memory fake for [EpisodeFactsRepository]. */
private class FakeEpisodeFactsRepository(seed: List<EpisodeFacts>) : EpisodeFactsRepository {
    private val state = MutableStateFlow(seed)

    override fun observeAll(): Flow<List<EpisodeFacts>> = state.asStateFlow()
}

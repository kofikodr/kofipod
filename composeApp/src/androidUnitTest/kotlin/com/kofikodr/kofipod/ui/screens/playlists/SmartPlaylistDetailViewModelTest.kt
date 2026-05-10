// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.playlists

import com.kofikodr.kofipod.playlists.EpisodeFacts
import com.kofikodr.kofipod.playlists.EpisodeFactsRepository
import com.kofikodr.kofipod.playlists.PlayState
import com.kofikodr.kofipod.playlists.PredicateEvaluator
import com.kofikodr.kofipod.playlists.SmartPlaylist
import com.kofikodr.kofipod.playlists.SmartPlaylistPredicate
import com.kofikodr.kofipod.playlists.SmartPlaylistRepository
import com.kofikodr.kofipod.playlists.SmartPlaylistResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
 * Unit tests for [SmartPlaylistDetailViewModel].
 *
 * Mirrors the editor-VM test fakes in this file's sibling
 * [SmartPlaylistEditorViewModelTest]: an in-memory [DetailFakePlaylistRepo] (capturing
 * deletes for assertion) and a [DetailFakeFactsRepo] streaming a deterministic
 * `List<EpisodeFacts>`. We keep [PredicateEvaluator] real — it's pure and deterministic;
 * faking it would skip the integration boundary the resolver actually relies on.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SmartPlaylistDetailViewModelTest {
    private val fixedNow = Instant.fromEpochMilliseconds(1_700_000_000_000L)
    private val fixedClock =
        object : Clock {
            override fun now(): Instant = fixedNow
        }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * Routes `Dispatchers.Main` to the test scheduler so the VM's `viewModelScope.launch`
     * (Main.immediate by default) is drained by `advanceUntilIdle()`.
     */
    private fun runVmTest(block: suspend TestScope.() -> Unit) =
        runTest {
            Dispatchers.setMain(UnconfinedTestDispatcher(testScheduler))
            block()
        }

    private fun fact(
        episodeId: String,
        episodeTitle: String,
        playState: PlayState = PlayState.Unplayed,
    ) = EpisodeFacts(
        episodeId = episodeId,
        episodeTitle = episodeTitle,
        podcastId = "p1",
        publishedAtMs = fixedNow.toEpochMilliseconds() - 86_400_000L,
        durationSec = 1_800,
        transcriptUrl = null,
        hasCachedTranscript = false,
        hasSnippets = false,
        isDownloaded = false,
        playState = playState,
    )

    private data class Harness(
        val vm: SmartPlaylistDetailViewModel,
        val playlists: DetailFakePlaylistRepo,
    )

    private fun harness(
        playlistId: String,
        seedPlaylists: List<SmartPlaylist> = emptyList(),
        seedFacts: List<EpisodeFacts> = emptyList(),
    ): Harness {
        val playlistsRepo = DetailFakePlaylistRepo().apply { seedPlaylists.forEach { saveSync(it) } }
        val factsRepo = DetailFakeFactsRepo(seedFacts)
        val resolver = SmartPlaylistResolver(facts = factsRepo, evaluator = PredicateEvaluator(), clock = fixedClock)
        val vm =
            SmartPlaylistDetailViewModel(
                playlists = playlistsRepo,
                resolver = resolver,
                playlistId = playlistId,
            )
        return Harness(vm, playlistsRepo)
    }

    @Test
    fun loadsPlaylistAndMatchedFacts() =
        runVmTest {
            // Predicate filters to "Unplayed" only — 2 of the 3 facts should match.
            val playlist =
                SmartPlaylist(
                    id = "pl-1",
                    name = "Unplayed",
                    predicate = SmartPlaylistPredicate(state = PlayState.Unplayed),
                    createdAtMs = 100L,
                )
            val facts =
                listOf(
                    fact("e1", "Episode 1", playState = PlayState.Unplayed),
                    fact("e2", "Episode 2", playState = PlayState.Completed),
                    fact("e3", "Episode 3", playState = PlayState.Unplayed),
                )
            val h = harness(playlistId = "pl-1", seedPlaylists = listOf(playlist), seedFacts = facts)
            advanceUntilIdle()

            val s = h.vm.state.value
            assertEquals(playlist, s.playlist, "Playlist must be observed from the repository")
            assertEquals(2, s.matched.size, "Only the two Unplayed facts should match the predicate")
            assertEquals(setOf("e1", "e3"), s.matched.map { it.episodeId }.toSet())
            assertFalse(s.notFound, "notFound must stay false when the playlist exists")
        }

    @Test
    fun notFoundEmitsTrueWhenPlaylistMissing() =
        runVmTest {
            val h = harness(playlistId = "missing", seedPlaylists = emptyList())
            advanceUntilIdle()

            val s = h.vm.state.value
            assertTrue(s.notFound, "notFound must flip to true when the repository emits null for the id")
            assertNull(s.playlist)
            assertTrue(s.matched.isEmpty())
        }

    @Test
    fun deleteCallsRepoDelete() =
        runVmTest {
            val playlist =
                SmartPlaylist(
                    id = "pl-1",
                    name = "Disposable",
                    predicate = SmartPlaylistPredicate.EMPTY,
                    createdAtMs = 1L,
                )
            val h = harness(playlistId = "pl-1", seedPlaylists = listOf(playlist))
            advanceUntilIdle()

            h.vm.delete()
            advanceUntilIdle()

            assertEquals(listOf("pl-1"), h.playlists.deletedIds, "delete() must forward the playlistId to the repository")
        }
}

/**
 * In-memory fake mirroring the editor-test fake. Backed by a `MutableStateFlow<Map>` so
 * observers re-emit on every save/delete; captures invocations for test assertions.
 */
private class DetailFakePlaylistRepo : SmartPlaylistRepository {
    private val store = MutableStateFlow<Map<String, SmartPlaylist>>(emptyMap())
    val deletedIds: MutableList<String> = mutableListOf()

    fun saveSync(playlist: SmartPlaylist) {
        store.update { it + (playlist.id to playlist) }
    }

    override fun observeAll(): Flow<List<SmartPlaylist>> = store.asStateFlow().map { it.values.sortedBy { p -> p.createdAtMs } }

    override fun observe(id: String): Flow<SmartPlaylist?> = store.asStateFlow().map { it[id] }

    override suspend fun save(playlist: SmartPlaylist) {
        store.update { it + (playlist.id to playlist) }
    }

    override suspend fun delete(id: String) {
        deletedIds += id
        store.update { it - id }
    }
}

/** In-memory fake for [EpisodeFactsRepository]. */
private class DetailFakeFactsRepo(seed: List<EpisodeFacts>) : EpisodeFactsRepository {
    private val state = MutableStateFlow(seed)

    override fun observeAll(): Flow<List<EpisodeFacts>> = state.asStateFlow()
}

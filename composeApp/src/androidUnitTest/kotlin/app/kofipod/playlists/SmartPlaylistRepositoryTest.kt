// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.playlists

import app.kofipod.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SmartPlaylistRepositoryTest {
    private fun newRepo(): SmartPlaylistRepositoryImpl = SmartPlaylistRepositoryImpl(inMemoryDatabase())

    @Test
    fun saveAndObserve() =
        runTest {
            val repo = newRepo()
            val pl =
                SmartPlaylist(
                    id = "p1",
                    name = "Recent unplayed",
                    predicate = SmartPlaylistPredicate(state = PlayState.Unplayed, maxAgeDays = 7),
                    createdAtMs = 1L,
                )
            repo.save(pl)
            val observed = repo.observe("p1").first()
            assertEquals(pl, observed)
        }

    @Test
    fun observeAllOrdersByCreatedAtAsc() =
        runTest {
            val repo = newRepo()
            repo.save(SmartPlaylist("a", "A", SmartPlaylistPredicate.EMPTY, 200L))
            repo.save(SmartPlaylist("b", "B", SmartPlaylistPredicate.EMPTY, 100L))
            val all = repo.observeAll().first()
            assertEquals(listOf("b", "a"), all.map { it.id })
        }

    @Test
    fun deleteRemoves() =
        runTest {
            val repo = newRepo()
            repo.save(SmartPlaylist("p1", "n", SmartPlaylistPredicate.EMPTY, 1L))
            repo.delete("p1")
            assertNull(repo.observe("p1").first())
        }

    @Test
    fun roundTripPredicateThroughPersistence() =
        runTest {
            val repo = newRepo()
            val pred =
                SmartPlaylistPredicate(
                    state = PlayState.InProgress,
                    durationRange = DurationRange(minSec = 60, maxSec = 600),
                    podcastIds = setOf("podA"),
                    hasSnippets = true,
                )
            repo.save(SmartPlaylist("p1", "Mix", pred, 1L))
            val observed = repo.observe("p1").first()
            assertEquals(pred, observed?.predicate)
        }

    @Test
    fun upsertReplacesOnSameId() =
        runTest {
            val repo = newRepo()
            repo.save(SmartPlaylist("p1", "v1", SmartPlaylistPredicate.EMPTY, 100L))
            repo.save(SmartPlaylist("p1", "v2", SmartPlaylistPredicate(maxAgeDays = 7), 200L))
            val observed = repo.observe("p1").first()
            assertEquals("v2", observed?.name)
            assertEquals(7, observed?.predicate?.maxAgeDays)
            assertEquals(1, repo.observeAll().first().size)
        }

    @Test
    fun decodeFallsBackToEmptyOnCorruptJson() =
        runTest {
            // Forward-compat: a row with predicateJson that fails to decode (e.g. from a future
            // schema) should not crash the observe flow — emit EMPTY predicate so the UI can
            // still show the row.
            val db = inMemoryDatabase()
            db.smartPlaylistQueries.upsert(
                id = "p1",
                name = "broken",
                predicateJson = "not json at all",
                createdAt = 1L,
            )
            val repo = SmartPlaylistRepositoryImpl(db)
            val observed = repo.observe("p1").first()
            assertEquals(SmartPlaylistPredicate.EMPTY, observed?.predicate)
        }
}

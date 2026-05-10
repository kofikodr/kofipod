// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.bookmarks

import com.kofikodr.kofipod.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookmarkRepositoryTest {
    private fun seedEpisode(
        db: com.kofikodr.kofipod.db.KofipodDatabase,
        podcastId: String = "pod-1",
        episodeId: String = "ep-1",
    ) {
        db.podcastQueries.insert(
            id = podcastId,
            title = "Test Show",
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
        db.episodeQueries.insert(
            id = episodeId,
            podcastId = podcastId,
            guid = episodeId,
            title = "Test Episode",
            description = "",
            publishedAt = 0,
            durationSec = 3600,
            enclosureUrl = "https://example.test/ep.mp3",
            enclosureMimeType = "audio/mpeg",
            fileSizeBytes = 0,
            seasonNumber = null,
            episodeNumber = null,
            imageUrl = "",
            chaptersUrl = null,
            transcriptUrl = null,
        )
    }

    @Test
    fun add_writesRow_withProvidedFields() {
        val db = inMemoryDatabase()
        seedEpisode(db)
        val repo = BookmarkRepository(db)

        val id =
            repo.add(
                episodeId = "ep-1",
                podcastId = "pod-1",
                timestampMs = 60_000L,
                note = "good moment",
                nowMs = 1_700_000_000_000L,
            )

        val rows = db.bookmarkQueries.selectByEpisode("ep-1").executeAsList()
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(id, row.id)
        assertEquals("ep-1", row.episodeId)
        assertEquals("pod-1", row.podcastId)
        assertEquals(60_000L, row.timestampMs)
        assertEquals("good moment", row.note)
        assertEquals(1_700_000_000_000L, row.createdAtMs)
    }

    @Test
    fun add_normalisesBlankNote_toNull() {
        val db = inMemoryDatabase()
        seedEpisode(db)
        val repo = BookmarkRepository(db)

        repo.add(
            episodeId = "ep-1",
            podcastId = "pod-1",
            timestampMs = 1_000L,
            note = "   ",
            nowMs = 100L,
        )

        val row = db.bookmarkQueries.selectByEpisode("ep-1").executeAsOne()
        assertNull(row.note)
    }

    @Test
    fun updateNote_normalisesBlankNote_toNull() {
        val db = inMemoryDatabase()
        seedEpisode(db)
        val repo = BookmarkRepository(db)
        val id =
            repo.add(
                episodeId = "ep-1",
                podcastId = "pod-1",
                timestampMs = 1_000L,
                note = "original",
                nowMs = 100L,
            )

        repo.updateNote(id, "   ")

        val row = db.bookmarkQueries.selectByEpisode("ep-1").executeAsOne()
        assertNull(row.note)
    }

    @Test
    fun deleteById_removesRow() {
        val db = inMemoryDatabase()
        seedEpisode(db)
        val repo = BookmarkRepository(db)
        val id =
            repo.add(
                episodeId = "ep-1",
                podcastId = "pod-1",
                timestampMs = 60_000L,
                note = null,
                nowMs = 100L,
            )

        assertEquals(1, db.bookmarkQueries.selectByEpisode("ep-1").executeAsList().size)
        repo.deleteById(id)
        assertEquals(0, db.bookmarkQueries.selectByEpisode("ep-1").executeAsList().size)
    }

    @Test
    fun observeForEpisode_returnsRowsOrderedByTimestamp_andUpdatesOnInsert() =
        runTest {
            val db = inMemoryDatabase()
            seedEpisode(db)
            val repo = BookmarkRepository(db)

            // Initial snapshot: insert two bookmarks in non-ascending timestamp order.
            repo.add("ep-1", "pod-1", timestampMs = 120_000L, note = null, nowMs = 100L)
            repo.add("ep-1", "pod-1", timestampMs = 30_000L, note = "early", nowMs = 200L)

            val flow = repo.observeForEpisode("ep-1")
            val initialRows = flow.first { it.size == 2 }
            assertEquals(listOf(30_000L, 120_000L), initialRows.map { it.timestampMs })
            assertEquals("early", initialRows[0].note)
            assertNull(initialRows[1].note)

            // Live update: a new insert should produce a fresh emission with the
            // additional row, still ordered by timestampMs ASC. Pins the asFlow() +
            // mapToList(...) reactivity contract — a refactor to a one-shot read
            // (e.g. flowOf(executeAsList())) would fail this assertion.
            repo.add("ep-1", "pod-1", timestampMs = 60_000L, note = "later", nowMs = 300L)

            val updatedRows = flow.first { it.size == 3 }
            assertEquals(listOf(30_000L, 60_000L, 120_000L), updatedRows.map { it.timestampMs })
        }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.bookmarks

import app.kofipod.testing.inMemoryDatabase
import org.junit.Test
import kotlin.test.assertEquals

class BookmarkRepositoryTest {
    private fun seedEpisode(
        db: app.kofipod.db.KofipodDatabase,
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
}

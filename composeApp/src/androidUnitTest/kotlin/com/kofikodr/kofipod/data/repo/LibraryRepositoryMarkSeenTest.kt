// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.repo

import com.kofikodr.kofipod.db.KofipodDatabase
import com.kofikodr.kofipod.testing.inMemoryDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LibraryRepositoryMarkSeenTest {
    private lateinit var db: KofipodDatabase
    private lateinit var repo: LibraryRepository

    @BeforeTest
    fun setUp() {
        db = inMemoryDatabase()
        repo = LibraryRepository(db)
        db.podcastQueries.insert(
            id = "p1", title = "Show", author = "", description = "",
            artworkUrl = "", feedUrl = "f", listId = null, autoDownloadEnabled = 0,
            notifyNewEpisodesEnabled = 1, lastCheckedAt = null, addedAt = 0,
            primaryCategory = "",
        )
    }

    @Test
    fun markSeen_persistsWatermark() {
        repo.markSeen("p1", 5_000L)
        assertEquals(5_000L, db.podcastQueries.selectById("p1").executeAsOne().lastSeenAt)
    }

    @Test
    fun markSeen_clearsNewCountForEpisodesPublishedBeforeIt() {
        // One episode published at t=1000 → new before markSeen.
        db.episodeQueries.insert(
            id = "e1", podcastId = "p1", guid = "g", title = "t", description = "",
            publishedAt = 1_000, durationSec = 1, enclosureUrl = "",
            enclosureMimeType = "audio/mpeg", fileSizeBytes = 0, seasonNumber = null,
            episodeNumber = null, imageUrl = "", chaptersUrl = null, transcriptUrl = null,
        )
        val before = db.episodeQueries.selectNewEpisodeCountsByPodcast().executeAsList()
        assertEquals(1, before.size)

        repo.markSeen("p1", 2_000L)

        val after = db.episodeQueries.selectNewEpisodeCountsByPodcast().executeAsList()
        assertEquals(0, after.size, "episode published (1000) before watermark (2000) must no longer be new")
    }
}

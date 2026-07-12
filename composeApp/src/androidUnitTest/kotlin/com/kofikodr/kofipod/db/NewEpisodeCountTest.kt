// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.db

import com.kofikodr.kofipod.testing.inMemoryDatabase
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks the semantics of the library "new" indicator: an episode is "new" when it has
 * no PlaybackState row (never started) AND was published after the later of
 * {podcast addedAt, podcast lastSeenAt}. Covers the watermark introduced for
 * implicit "mark seen" plus the retained play-to-dismiss channel.
 */
class NewEpisodeCountTest {
    private lateinit var db: KofipodDatabase

    @BeforeTest
    fun setUp() {
        db = inMemoryDatabase()
        // Podcast added at t=100. Three episodes: one before the add (back catalog),
        // two after.
        db.podcastQueries.insert(
            id = "p1", title = "Show", author = "", description = "",
            artworkUrl = "", feedUrl = "f", listId = null, autoDownloadEnabled = 0,
            notifyNewEpisodesEnabled = 1, lastCheckedAt = null, addedAt = 100,
            primaryCategory = "",
        )
        episode("eOld", publishedAt = 50)
        episode("eMid", publishedAt = 150)
        episode("eNew", publishedAt = 250)
    }

    private fun episode(id: String, publishedAt: Long) =
        db.episodeQueries.insert(
            id = id, podcastId = "p1", guid = id, title = id, description = "",
            publishedAt = publishedAt, durationSec = 1, enclosureUrl = "",
            enclosureMimeType = "audio/mpeg", fileSizeBytes = 0, seasonNumber = null,
            episodeNumber = null, imageUrl = "", chaptersUrl = null, transcriptUrl = null,
        )

    private fun counts(): Map<String, Long> =
        db.episodeQueries.selectNewEpisodeCountsByPodcast()
            .executeAsList()
            .associate { it.podcastId to it.newCount }

    private fun markPlayed(episodeId: String) =
        db.playbackStateQueries.upsert(
            episodeId = episodeId, positionMs = 0, durationMs = 1, completedAt = 1,
            playbackSpeed = 1.0, updatedAt = 1, episodeTitle = "", podcastId = "p1",
            podcastTitle = "", artworkUrl = "", sourceUrl = "", episodeNumber = null,
        )

    @Test
    fun nullWatermark_countsEpisodesPublishedAfterAddedAt() {
        // eOld (50) excluded by addedAt (100); eMid + eNew counted.
        assertEquals(mapOf("p1" to 2L), counts())
    }

    @Test
    fun watermark_excludesEpisodesPublishedAtOrBeforeIt() {
        db.podcastQueries.setLastSeen(200, "p1")
        // new = published > max(addedAt=100, lastSeenAt=200) = 200 → only eNew (250).
        assertEquals(mapOf("p1" to 1L), counts())
    }

    @Test
    fun watermarkAfterNewest_dropsPodcastEntirely() {
        db.podcastQueries.setLastSeen(300, "p1")
        // Nothing published after 300 → p1 absent from the result map.
        assertEquals(emptyMap(), counts())
    }

    @Test
    fun playbackRow_stillDismissesIndependentlyOfWatermark() {
        // No watermark set; mark eMid played. It drops even though published > addedAt,
        // proving play-to-dismiss survives alongside the watermark. eNew remains.
        markPlayed("eMid")
        assertEquals(mapOf("p1" to 1L), counts())
    }
}

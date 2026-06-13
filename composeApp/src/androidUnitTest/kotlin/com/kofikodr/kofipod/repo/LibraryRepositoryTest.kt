// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.repo

import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.testing.inMemoryDatabaseWithDriver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class LibraryRepositoryTest {
    @Test
    fun savePodcast_existingPodcast_updatesMetadataWithoutCascadingChildren() {
        val (db, driver) = inMemoryDatabaseWithDriver()
        try {
            val library = LibraryRepository(db)
            library.createList(id = "original-list", name = "Original List", position = 0, now = 50L)
            library.createList(id = "incoming-list", name = "Incoming List", position = 1, now = 60L)
            library.savePodcast(summary(title = "Original", artworkUrl = "old.png"), listId = "original-list", now = 100L)
            library.setAutoDownload(PODCAST_ID, enabled = true)
            library.setNotifyNewEpisodes(PODCAST_ID, enabled = false)
            library.setLastChecked(PODCAST_ID, atMillis = 900L)
            db.episodeQueries.insert(
                id = EPISODE_ID,
                podcastId = PODCAST_ID,
                guid = "guid-1",
                title = "Episode",
                description = "Description",
                publishedAt = 1_000L,
                durationSec = 120L,
                enclosureUrl = "https://example.com/episode.mp3",
                enclosureMimeType = "audio/mpeg",
                fileSizeBytes = 1_024L,
                seasonNumber = null,
                episodeNumber = null,
                imageUrl = "",
                chaptersUrl = null,
                transcriptUrl = null,
            )
            db.bookmarkQueries.insert(
                id = "bookmark-1",
                episodeId = EPISODE_ID,
                podcastId = PODCAST_ID,
                timestampMs = 42_000L,
                note = "Keep me",
                createdAtMs = 1_100L,
            )
            db.downloadQueries.upsert(
                episodeId = EPISODE_ID,
                state = "Completed",
                localPath = "/tmp/episode.mp3",
                downloadedBytes = 1_024L,
                totalBytes = 1_024L,
                source = "Manual",
                startedAt = 1_200L,
                completedAt = 1_300L,
                errorMessage = null,
            )
            db.episodeAiSummaryQueries.upsert(
                episodeId = EPISODE_ID,
                generatedAtMs = 1_400L,
                modelId = "model",
                sourceKind = "transcript",
                sourceFingerprint = "fingerprint",
                summary = "Keep this summary",
                peopleJson = "[]",
                thingsJson = "[]",
                linksJson = "[]",
            )

            library.savePodcast(summary(title = "Updated", artworkUrl = "new.png"), listId = "ignored", now = 999L)

            val podcast = library.podcastNow(PODCAST_ID)
            assertNotNull(podcast)
            assertEquals("Updated", podcast.title)
            assertEquals("Updated Author", podcast.author)
            assertEquals("Updated Description", podcast.description)
            assertEquals("new.png", podcast.artworkUrl)
            assertEquals("https://example.com/updated.xml", podcast.feedUrl)
            assertEquals("News", podcast.primaryCategory)
            assertEquals("original-list", podcast.listId, "Duplicate saves must preserve existing folder membership")
            assertEquals(100L, podcast.addedAt, "Duplicate saves must preserve the original library timestamp")
            assertEquals(1L, podcast.autoDownloadEnabled, "Duplicate saves must preserve auto-download")
            assertEquals(0L, podcast.notifyNewEpisodesEnabled, "Duplicate saves must preserve notification preference")
            assertEquals(900L, podcast.lastCheckedAt, "Duplicate saves must preserve the last episode check timestamp")
            val episode = db.episodeQueries.selectByPodcast(PODCAST_ID).executeAsList().single()
            assertEquals("Episode", episode.title)
            val bookmark = db.bookmarkQueries.selectByEpisode(EPISODE_ID).executeAsList().single()
            assertEquals("Keep me", bookmark.note)
            val download = db.downloadQueries.selectByEpisode(EPISODE_ID).executeAsOneOrNull()
            assertNotNull(download)
            assertEquals("Completed", download.state)
            assertEquals("/tmp/episode.mp3", download.localPath)
            val aiSummary = db.episodeAiSummaryQueries.selectByEpisode(EPISODE_ID).executeAsOneOrNull()
            assertNotNull(aiSummary)
            assertEquals("Keep this summary", aiSummary.summary)
        } finally {
            driver.close()
        }
    }

    private fun summary(
        title: String,
        artworkUrl: String,
    ): PodcastSummary =
        PodcastSummary(
            id = PODCAST_ID,
            feedId = 123L,
            title = title,
            author = "$title Author",
            description = "$title Description",
            artworkUrl = artworkUrl,
            feedUrl = "https://example.com/${title.lowercase()}.xml",
            category = if (title == "Updated") "News" else "Technology",
        )

    private companion object {
        const val PODCAST_ID = "123"
        const val EPISODE_ID = "episode-1"
    }
}

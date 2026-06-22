// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.playlists

import com.kofikodr.kofipod.db.KofipodDatabase
import com.kofikodr.kofipod.snippets.FileCheckerApi
import com.kofikodr.kofipod.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioural tests for [EpisodeFactsRepositoryImpl]'s `isDownloaded` derivation
 * (issue #33). A `Download` row in `state='Completed'` only counts as downloaded
 * when its `localPath` file actually exists on disk — otherwise a
 * `downloadedOnly` smart playlist would surface an episode that cannot play
 * offline, disagreeing with `DownloadRepository.localPathFor`'s self-heal.
 */
class EpisodeFactsRepositoryImplTest {
    @Test
    fun completedDownloadWithExistingFile_isDownloaded() =
        runTest {
            val db = inMemoryDatabase()
            seedEpisode(db, "ep-1")
            insertCompleted(db, "ep-1", localPath = "/downloads/ep-1.mp3")
            val repo = EpisodeFactsRepositoryImpl(db, FakeFileChecker(setOf("/downloads/ep-1.mp3")))

            val facts = repo.observeAll().first().single()

            assertTrue(facts.isDownloaded, "a Completed download whose file exists must count as downloaded")
        }

    @Test
    fun completedDownloadWithMissingFile_isNotDownloaded() =
        runTest {
            // The headline of #33: the row says Completed but the file is gone
            // (external delete / DB-only restore). Pre-fix this returned true and
            // the episode leaked into downloaded-only playlists.
            val db = inMemoryDatabase()
            seedEpisode(db, "ep-1")
            insertCompleted(db, "ep-1", localPath = "/downloads/ep-1.mp3")
            val repo = EpisodeFactsRepositoryImpl(db, FakeFileChecker(existing = emptySet()))

            val facts = repo.observeAll().first().single()

            assertFalse(
                facts.isDownloaded,
                "a Completed download whose file is missing must NOT count as downloaded",
            )
        }

    @Test
    fun completedDownloadWithNullLocalPath_isNotDownloaded() =
        runTest {
            // Defensive: a Completed row with no localPath has nothing to verify on
            // disk, so it cannot be played offline either.
            val db = inMemoryDatabase()
            seedEpisode(db, "ep-1")
            insertCompleted(db, "ep-1", localPath = null)
            val repo = EpisodeFactsRepositoryImpl(db, AlwaysExistsFileChecker)

            val facts = repo.observeAll().first().single()

            assertFalse(facts.isDownloaded, "a Completed row without a localPath cannot be downloaded")
        }

    @Test
    fun completedDownloadWithEmptyLocalPath_isNotDownloaded() =
        runTest {
            // Defensive: an empty-string localPath is non-null, so the existence check
            // IS consulted (unlike the null case) and returns false — no file at "".
            // Mirrors the real FileChecker, which treats a blank path as not existing.
            val db = inMemoryDatabase()
            seedEpisode(db, "ep-1")
            insertCompleted(db, "ep-1", localPath = "")
            val repo = EpisodeFactsRepositoryImpl(db, FakeFileChecker(existing = emptySet()))

            val facts = repo.observeAll().first().single()

            assertFalse(facts.isDownloaded, "a Completed row with an empty localPath cannot be downloaded")
        }

    @Test
    fun nonCompletedDownload_isNotDownloaded_withoutTouchingDisk() =
        runTest {
            // An in-progress / queued row is never downloaded regardless of disk,
            // and the file check must not even be consulted for it.
            val db = inMemoryDatabase()
            seedEpisode(db, "ep-1")
            db.downloadQueries.upsert(
                episodeId = "ep-1",
                state = "Downloading",
                localPath = null,
                downloadedBytes = 10L,
                totalBytes = 100L,
                source = "Manual",
                startedAt = 0L,
                completedAt = null,
                errorMessage = null,
            )
            val checker = ThrowingFileChecker
            val repo = EpisodeFactsRepositoryImpl(db, checker)

            val facts = repo.observeAll().first().single()

            assertFalse(facts.isDownloaded)
        }

    @Test
    fun noDownloadRow_isNotDownloaded() =
        runTest {
            val db = inMemoryDatabase()
            seedEpisode(db, "ep-1")
            val repo = EpisodeFactsRepositoryImpl(db, ThrowingFileChecker)

            val facts = repo.observeAll().first().single()

            assertEquals("ep-1", facts.episodeId)
            assertFalse(facts.isDownloaded)
        }

    // ─── helpers ────────────────────────────────────────────────────────────

    private fun seedEpisode(
        db: KofipodDatabase,
        id: String,
    ) {
        db.podcastQueries.insert(
            id = "p-$id",
            title = "Podcast $id",
            author = "",
            description = "",
            artworkUrl = "",
            feedUrl = "",
            listId = null,
            autoDownloadEnabled = 0L,
            notifyNewEpisodesEnabled = 1L,
            lastCheckedAt = 0L,
            addedAt = 0L,
            primaryCategory = "",
        )
        db.episodeQueries.insert(
            id = id,
            podcastId = "p-$id",
            guid = id,
            title = "Ep $id",
            description = "",
            publishedAt = 0L,
            durationSec = 0L,
            enclosureUrl = "https://example.com/$id",
            enclosureMimeType = "audio/mpeg",
            fileSizeBytes = 0L,
            seasonNumber = null,
            episodeNumber = null,
            imageUrl = "",
            chaptersUrl = null,
            transcriptUrl = null,
        )
    }

    private fun insertCompleted(
        db: KofipodDatabase,
        episodeId: String,
        localPath: String?,
    ) {
        db.downloadQueries.upsert(
            episodeId = episodeId,
            state = "Completed",
            localPath = localPath,
            downloadedBytes = 100L,
            totalBytes = 100L,
            source = "Manual",
            startedAt = 0L,
            completedAt = 1L,
            errorMessage = null,
        )
    }

    private class FakeFileChecker(private val existing: Set<String>) : FileCheckerApi {
        override fun exists(path: String): Boolean = path in existing
    }

    private object AlwaysExistsFileChecker : FileCheckerApi {
        override fun exists(path: String): Boolean = true
    }

    /** Fails the test if consulted — proves a branch never touches the disk. */
    private object ThrowingFileChecker : FileCheckerApi {
        override fun exists(path: String): Boolean =
            throw AssertionError("file existence must not be checked when there is no completed local path")
    }
}

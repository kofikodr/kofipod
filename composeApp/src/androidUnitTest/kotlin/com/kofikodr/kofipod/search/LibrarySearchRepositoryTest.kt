// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.search

import app.cash.sqldelight.db.SqlDriver
import com.kofikodr.kofipod.db.KofipodDatabase
import com.kofikodr.kofipod.testing.inMemoryDatabaseWithDriver
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibrarySearchRepositoryTest {
    private lateinit var db: KofipodDatabase
    private lateinit var driver: SqlDriver
    private lateinit var repo: LibrarySearchRepository

    @BeforeTest
    fun setUp() {
        val (database, sqlDriver) = inMemoryDatabaseWithDriver()
        db = database
        driver = sqlDriver
        repo = LibrarySearchRepository(driver = driver)

        // Seed: one podcast, one episode. Mirrors BookmarkRepositoryTest's pattern.
        db.podcastQueries.insert(
            id = "p1", title = "AI Show", author = "", description = "",
            artworkUrl = "", feedUrl = "f", listId = null, autoDownloadEnabled = 0,
            notifyNewEpisodesEnabled = 1, lastCheckedAt = null, addedAt = 0,
            primaryCategory = "",
        )
        db.episodeQueries.insert(
            id = "e1", podcastId = "p1", guid = "g", title = "Why continual learning matters",
            description = "", publishedAt = 0, durationSec = 1000, enclosureUrl = "",
            enclosureMimeType = "audio/mpeg", fileSizeBytes = 0, seasonNumber = null,
            episodeNumber = null, imageUrl = "", chaptersUrl = null, transcriptUrl = null,
        )
    }

    @AfterTest
    fun tearDown() = driver.close()

    @Test
    fun bookmark_insert_isSearchableByNote() =
        runTest {
            db.bookmarkQueries.insert(
                id = "b1",
                episodeId = "e1",
                podcastId = "p1",
                timestampMs = 5_000L,
                note = "great quote about learning",
                createdAtMs = 1L,
            )

            val results = repo.search("learning").first()
            assertEquals(1, results.size)
            val hit = results.single() as LibrarySearchResult.BookmarkMatch
            assertEquals("b1", hit.bookmarkId)
            assertEquals(5_000L, hit.timestampMs)
            assertTrue(hit.excerpt.contains("learning", ignoreCase = true))
            assertTrue(
                hit.excerpt.contains("<<"),
                "snippet() should wrap matches in '<<' markers; an empty/raw excerpt suggests the column index argument is wrong",
            )
        }

    @Test
    fun summary_insert_isSearchableByContent() =
        runTest {
            db.episodeAiSummaryQueries.upsert(
                episodeId = "e1", generatedAtMs = 0, modelId = "m",
                sourceKind = "transcript", sourceFingerprint = "fp",
                summary = "A discussion of continual learning in language models.",
                peopleJson = "[]", thingsJson = "[]", linksJson = "[]",
            )

            val results = repo.search("continual").first()
            assertEquals(1, results.size)
            assertTrue(results.single() is LibrarySearchResult.SummaryMatch)
        }

    @Test
    fun transcript_update_replacesOldText() =
        runTest {
            db.transcriptCacheQueries.upsert(episodeId = "e1", text = "hello banana world", fetchedAtMs = 1)
            val before = repo.search("banana").first()
            assertEquals(1, before.size)
            assertTrue(before.single() is LibrarySearchResult.TranscriptMatch)

            db.transcriptCacheQueries.upsert(episodeId = "e1", text = "hello cherry world", fetchedAtMs = 2)
            assertEquals(0, repo.search("banana").first().size)
            assertEquals(1, repo.search("cherry").first().size)
        }

    @Test
    fun bookmark_delete_removesIndexRow() =
        runTest {
            db.bookmarkQueries.insert(
                id = "b1",
                episodeId = "e1",
                podcastId = "p1",
                timestampMs = 0,
                note = "uniquephrase",
                createdAtMs = 0,
            )
            assertEquals(1, repo.search("uniquephrase").first().size)

            db.bookmarkQueries.deleteById("b1")
            assertEquals(0, repo.search("uniquephrase").first().size)
        }

    @Test
    fun episode_cascade_deletesAllIndexRowsForThatEpisode() =
        runTest {
            db.bookmarkQueries.insert(
                id = "b1",
                episodeId = "e1",
                podcastId = "p1",
                timestampMs = 0,
                note = "alpha",
                createdAtMs = 0,
            )
            db.episodeAiSummaryQueries.upsert(
                episodeId = "e1", generatedAtMs = 0, modelId = "m",
                sourceKind = "transcript", sourceFingerprint = "fp",
                summary = "alpha beta", peopleJson = "[]", thingsJson = "[]", linksJson = "[]",
            )
            db.transcriptCacheQueries.upsert(episodeId = "e1", text = "alpha gamma", fetchedAtMs = 0)
            assertEquals(3, repo.search("alpha").first().size)

            // Episode delete cascades to Bookmark + EpisodeAiSummary + TranscriptCache.
            // Their AFTER DELETE triggers fire to clear LibrarySearchIndex.
            // SQLite requires PRAGMA foreign_keys = ON for cascades; the JdbcSqliteDriver
            // sets this by default via SQLDelight. If the test fails here with rows still
            // present, that's a sign FK enforcement is off — investigate before removing.
            db.episodeQueries.delete("e1")
            assertEquals(0, repo.search("alpha").first().size)
        }

    @Test
    fun blankQuery_emitsEmpty_withoutHittingSqlite() =
        runTest {
            db.bookmarkQueries.insert(
                id = "b1",
                episodeId = "e1",
                podcastId = "p1",
                timestampMs = 0,
                note = "would-match",
                createdAtMs = 0,
            )
            assertEquals(0, repo.search("").first().size)
            assertEquals(0, repo.search("   ").first().size)
        }

    @Test
    fun summary_upsert_replacesOldText() =
        runTest {
            db.episodeAiSummaryQueries.upsert(
                episodeId = "e1", generatedAtMs = 0, modelId = "m",
                sourceKind = "transcript", sourceFingerprint = "fp",
                summary = "discussion of bananaword in detail",
                peopleJson = "[]", thingsJson = "[]", linksJson = "[]",
            )
            assertEquals(1, repo.search("bananaword").first().size, "AFTER INSERT trigger should make first upsert visible")

            db.episodeAiSummaryQueries.upsert(
                episodeId = "e1", generatedAtMs = 1, modelId = "m",
                sourceKind = "transcript", sourceFingerprint = "fp",
                summary = "discussion of cherryword in detail",
                peopleJson = "[]", thingsJson = "[]", linksJson = "[]",
            )
            assertEquals(0, repo.search("bananaword").first().size, "AFTER UPDATE trigger's DELETE step must purge the stale FTS row")
            assertEquals(1, repo.search("cherryword").first().size, "AFTER UPDATE trigger's INSERT step must add the new FTS row")
        }

    @Test
    fun summary_directDelete_removesIndexRow() =
        runTest {
            db.episodeAiSummaryQueries.upsert(
                episodeId = "e1", generatedAtMs = 0, modelId = "m",
                sourceKind = "transcript", sourceFingerprint = "fp",
                summary = "uniquesummaryphrase",
                peopleJson = "[]", thingsJson = "[]", linksJson = "[]",
            )
            assertEquals(1, repo.search("uniquesummaryphrase").first().size)

            // Direct delete (not via FK cascade) — proves summary_fts_ad uses the right predicate.
            db.episodeAiSummaryQueries.deleteByEpisode("e1")
            assertEquals(0, repo.search("uniquesummaryphrase").first().size)
        }

    @Test
    fun transcript_directDelete_removesIndexRow() =
        runTest {
            db.transcriptCacheQueries.upsert(episodeId = "e1", text = "uniquetranscriptphrase", fetchedAtMs = 0)
            assertEquals(1, repo.search("uniquetranscriptphrase").first().size)

            db.transcriptCacheQueries.deleteByEpisode("e1")
            assertEquals(0, repo.search("uniquetranscriptphrase").first().size)
        }

    @Test
    fun kindFilter_narrowsToSingleBucket() =
        runTest {
            db.bookmarkQueries.insert(
                id = "b1",
                episodeId = "e1",
                podcastId = "p1",
                timestampMs = 0,
                note = "shared word",
                createdAtMs = 0,
            )
            db.episodeAiSummaryQueries.upsert(
                episodeId = "e1", generatedAtMs = 0, modelId = "m",
                sourceKind = "transcript", sourceFingerprint = "fp",
                summary = "shared word", peopleJson = "[]", thingsJson = "[]", linksJson = "[]",
            )
            assertEquals(2, repo.search("shared").first().size)

            val onlyBookmarks = repo.search("shared", kind = LibrarySearchKind.Bookmark).first()
            assertEquals(1, onlyBookmarks.size)
            assertTrue(onlyBookmarks.single() is LibrarySearchResult.BookmarkMatch)
        }
}

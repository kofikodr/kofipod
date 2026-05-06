// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.kofipod.db.KofipodDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnippetRepositoryTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var db: KofipodDatabase
    private lateinit var repo: SnippetRepository

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KofipodDatabase.Schema.create(driver)
        db = KofipodDatabase(driver)
        repo = SnippetRepository(db)
        // Seed a podcast + episode so FKs are satisfied.
        db.podcastQueries.insert(
            id = "p1", title = "Pod", author = "Auth", description = "",
            artworkUrl = "", feedUrl = "https://x/y.xml", listId = null,
            autoDownloadEnabled = 0, notifyNewEpisodesEnabled = 1,
            lastCheckedAt = null, addedAt = 1L, primaryCategory = "Test",
        )
        db.episodeQueries.insert(
            id = "e1", podcastId = "p1", guid = "e1g", title = "Ep1",
            description = "", publishedAt = 1L, durationSec = 600,
            enclosureUrl = "https://x/e1.mp3", enclosureMimeType = "audio/mpeg",
            fileSizeBytes = 0L, seasonNumber = null, episodeNumber = 1,
            imageUrl = "", chaptersUrl = null, transcriptUrl = null,
        )
    }

    @AfterTest fun tearDown() = driver.close()

    @Test
    fun `createDraftFromPlayer persists a draft with last-60s window`() =
        runTest {
            val id =
                repo.createDraftFromPlayer(
                    episodeId = "e1",
                    podcastId = "p1",
                    playerPositionMs = 120_000L,
                    episodeDurationMs = 600_000L,
                    episodeTitle = "Ep1",
                    nowMs = 1_700_000_000L,
                )
            val s = repo.observeForEpisode("e1").first().single()
            assertEquals(id, s.id)
            assertEquals(60_000L, s.startMs)
            assertEquals(120_000L, s.endMs)
            assertEquals("Ep1 — 01:00.0", s.title) // default title format
            assertNull(s.lastExportFormat)
            assertNull(s.lastExportPath)
        }

    @Test
    fun `createDraftFromPlayer clamps start to zero when position is below 60s`() =
        runTest {
            val id =
                repo.createDraftFromPlayer(
                    episodeId = "e1",
                    podcastId = "p1",
                    playerPositionMs = 30_000L,
                    episodeDurationMs = 600_000L,
                    episodeTitle = "Ep1",
                    nowMs = 1L,
                )
            val s = repo.observeForEpisode("e1").first().single { it.id == id }
            assertEquals(0L, s.startMs)
            assertEquals(30_000L, s.endMs)
        }

    @Test
    fun `updateTrim mutates startMs and endMs`() =
        runTest {
            val id = repo.createDraftFromPlayer("e1", "p1", 120_000L, 600_000L, "Ep1", 1L)
            repo.updateTrim(id, 70_000L, 100_000L)
            val s = repo.observeForEpisode("e1").first().single { it.id == id }
            assertEquals(70_000L, s.startMs)
            assertEquals(100_000L, s.endMs)
        }

    @Test
    fun `updateTrim clamps invalid input via SnippetWindow rules`() =
        runTest {
            val id = repo.createDraftFromPlayer("e1", "p1", 120_000L, 600_000L, "Ep1", 1L)
            repo.updateTrim(id, -500L, 700_000L, durationMs = 600_000L)
            val s = repo.observeForEpisode("e1").first().single { it.id == id }
            assertEquals(0L, s.startMs)
            assertEquals(600_000L, s.endMs)
        }

    @Test
    fun `setRendered records format and path`() =
        runTest {
            val id = repo.createDraftFromPlayer("e1", "p1", 120_000L, 600_000L, "Ep1", 1L)
            repo.setRendered(id, SnippetFormat.MP3, "/data/cache/snippets/$id.mp3")
            val s = repo.observeForEpisode("e1").first().single { it.id == id }
            assertEquals(SnippetFormat.MP3, s.lastExportFormat)
            assertEquals("/data/cache/snippets/$id.mp3", s.lastExportPath)
            assertTrue(s.isRendered)
        }

    @Test
    fun `deleteById removes the row`() =
        runTest {
            val id = repo.createDraftFromPlayer("e1", "p1", 120_000L, 600_000L, "Ep1", 1L)
            repo.deleteById(id)
            assertEquals(emptyList(), repo.observeForEpisode("e1").first())
        }

    @Test
    fun `selectById returns null for missing row`() =
        runTest {
            assertNull(repo.selectById("nope"))
        }

    @Test
    fun `selectById returns the row when present`() =
        runTest {
            val id = repo.createDraftFromPlayer("e1", "p1", 120_000L, 600_000L, "Ep1", 1L)
            val s = repo.selectById(id)
            assertNotNull(s)
            assertEquals(id, s.id)
        }

    @Test
    fun `observeAllWithContext joins episode and podcast metadata`() =
        runTest {
            val id = repo.createDraftFromPlayer("e1", "p1", 120_000L, 600_000L, "Ep1", 1L)
            val all = repo.observeAllWithContext().first()
            assertEquals(1, all.size)
            assertEquals(id, all.single().snippet.id)
            assertEquals("Ep1", all.single().episodeTitle)
            assertEquals("Pod", all.single().podcastTitle)
        }

    @Test
    fun `episode delete cascades to snippets`() =
        runTest {
            repo.createDraftFromPlayer("e1", "p1", 120_000L, 600_000L, "Ep1", 1L)
            driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
            // Episode.sq's delete query is named `delete`, not `deleteById` (deviation
            // from plan text — actual schema seed correction).
            db.episodeQueries.delete("e1")
            assertEquals(emptyList(), repo.observeForEpisode("e1").first())
        }
}

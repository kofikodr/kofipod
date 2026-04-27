// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.repo

import app.kofipod.data.repo.PlaybackRepository
import app.kofipod.testing.inMemoryDatabase
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PlaybackRepositoryTest {
    @Test
    fun markCompleted_writesExpectedFields_whenNoPriorRowExists() {
        val db = inMemoryDatabase()
        val repo = PlaybackRepository(db)

        repo.markCompleted(
            episodeId = "ep-1",
            nowMillis = 1000L,
            currentDurationMs = 3_600_000L,
        )

        val row = db.playbackStateQueries.selectByEpisode("ep-1").executeAsOne()
        assertEquals(3_600_000L, row.positionMs, "positionMs should equal currentDurationMs")
        assertEquals(3_600_000L, row.durationMs, "durationMs should equal currentDurationMs")
        assertEquals(1000L, row.completedAt, "completedAt should be the nowMillis stamp")
        assertEquals("", row.sourceUrl, "sourceUrl should be blank when no prior row existed")
        assertEquals("", row.episodeTitle)
        assertEquals("", row.podcastId)
        assertEquals("", row.podcastTitle)
        assertEquals("", row.artworkUrl)
        assertNull(row.episodeNumber)

        // A completed row with blank sourceUrl is excluded from the "Continue listening" query.
        assertNull(repo.mostRecentIncomplete())
    }

    @Test
    fun markCompleted_withZeroDuration_andNoPriorRow_writesZeroPositionAndDuration() {
        // Locks in the current contract for the "completion event arrives before any save"
        // path: both positionMs and durationMs write as 0. This is known silent data-loss —
        // an episode that reached STATE_ENDED without ever emitting a 5 s save will look
        // un-played (0 / 0) in any UI that reads positionMs. If this behavior is ever
        // softened (e.g. falling back to the real duration from Episode), this test
        // documents the change point.
        val db = inMemoryDatabase()
        val repo = PlaybackRepository(db)

        repo.markCompleted(
            episodeId = "ep-silent",
            nowMillis = 500L,
            currentDurationMs = 0L,
        )

        val row = db.playbackStateQueries.selectByEpisode("ep-silent").executeAsOne()
        assertEquals(0L, row.positionMs, "positionMs falls back to currentDurationMs (0) with no prior row")
        assertEquals(0L, row.durationMs, "durationMs falls back to currentDurationMs (0) with no prior row")
        assertEquals(500L, row.completedAt, "completedAt should still be stamped")
    }

    @Test
    fun saveThenMarkCompleted_preservesMetadata_forNeverPlayedEpisode() {
        // This pins the contract that EpisodeDetailViewModel.markPlayed relies on:
        // a save() that seeds metadata immediately followed by markCompleted() must
        // leave the row in a "completed AND queryable" state. Without the save() seed,
        // markCompleted on a missing row writes empty strings for podcastId / title /
        // artworkUrl / sourceUrl, which orphans the row from JOIN-based queries used
        // by Continue Listening and the Stats screen.
        val db = inMemoryDatabase()
        val repo = PlaybackRepository(db)

        val durationMs = 90L * 60L * 1000L // 90 min
        val now = 1_700_000_000_000L

        repo.save(
            episodeId = "ep-mark-played",
            positionMs = durationMs,
            durationMs = durationMs,
            speed = 1f,
            updatedAt = now,
            episodeTitle = "Compiler ergonomics",
            podcastId = "pod-42",
            podcastTitle = "Signal & Noise",
            artworkUrl = "https://art.example/ep-mark-played.jpg",
            sourceUrl = "https://audio.example/ep-mark-played.mp3",
            episodeNumber = 214,
        )
        repo.markCompleted(
            episodeId = "ep-mark-played",
            nowMillis = now,
            currentDurationMs = durationMs,
        )

        val row = db.playbackStateQueries.selectByEpisode("ep-mark-played").executeAsOne()
        assertEquals("Compiler ergonomics", row.episodeTitle, "episodeTitle must survive the markCompleted call")
        assertEquals("pod-42", row.podcastId, "podcastId is what JOIN queries depend on; losing it orphans the row")
        assertEquals("Signal & Noise", row.podcastTitle)
        assertEquals("https://art.example/ep-mark-played.jpg", row.artworkUrl)
        assertEquals("https://audio.example/ep-mark-played.mp3", row.sourceUrl)
        assertEquals(214L, row.episodeNumber)
        assertEquals(durationMs, row.positionMs)
        assertEquals(durationMs, row.durationMs)
        assertNotNull(row.completedAt, "completedAt must be stamped after markCompleted")
        assertEquals(now, row.completedAt)
    }

    @Test
    fun mostRecentIncomplete_returnsMostRecent_andExcludesCompletedAndEmptySourceUrl() {
        val db = inMemoryDatabase()
        val repo = PlaybackRepository(db)

        repo.save(
            episodeId = "ep-A",
            positionMs = 500L,
            durationMs = 10_000L,
            speed = 1.0f,
            updatedAt = 100L,
            episodeTitle = "Ep A",
            podcastId = "pod",
            podcastTitle = "Pod",
            artworkUrl = "",
            sourceUrl = "http://a.mp3",
            episodeNumber = null,
        )
        repo.save(
            episodeId = "ep-B",
            positionMs = 500L,
            durationMs = 10_000L,
            speed = 1.0f,
            updatedAt = 200L,
            episodeTitle = "Ep B",
            podcastId = "pod",
            podcastTitle = "Pod",
            artworkUrl = "",
            sourceUrl = "http://b.mp3",
            episodeNumber = null,
        )
        // Legacy / migrated row — no sourceUrl yet, so it's not resumable.
        repo.save(
            episodeId = "ep-C",
            positionMs = 500L,
            durationMs = 10_000L,
            speed = 1.0f,
            updatedAt = 300L,
            episodeTitle = "Ep C",
            podcastId = "pod",
            podcastTitle = "Pod",
            artworkUrl = "",
            sourceUrl = "",
            episodeNumber = null,
        )

        assertEquals(
            "ep-B",
            repo.mostRecentIncomplete()?.episodeId,
            "ep-C should be skipped (blank sourceUrl); ep-B wins by updatedAt over ep-A",
        )

        // Marking ep-B completed should drop it from the incomplete result set.
        repo.markCompleted(episodeId = "ep-B", nowMillis = 400L)
        assertEquals(
            "ep-A",
            repo.mostRecentIncomplete()?.episodeId,
            "after ep-B is completed, the next best incomplete row is ep-A",
        )
    }
}

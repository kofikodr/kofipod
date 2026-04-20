// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.repo

import app.kofipod.data.repo.PlaybackRepository
import app.kofipod.testing.inMemoryDatabase
import org.junit.Test
import kotlin.test.assertEquals
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

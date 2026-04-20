// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.repo

import app.kofipod.data.repo.DownloadRepository
import app.kofipod.downloads.DownloadEngineApi
import app.kofipod.downloads.DownloadJob
import app.kofipod.downloads.DownloadProgress
import app.kofipod.testing.inMemoryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DownloadRepositoryTest {
    @Test
    fun localUriFor_returnsFileUri_forCompletedDownloads_andNullOtherwise() {
        val db = inMemoryDatabase()
        val engine = FakeDownloadEngine()
        val scope = CoroutineScope(Dispatchers.Unconfined + SupervisorJob())
        val repo = DownloadRepository(db, engine, scope)

        // Insert a completed download row directly via the generated query.
        db.downloadQueries.upsert(
            episodeId = "ep-done",
            state = "Completed",
            localPath = "/path/ep-done.mp3",
            downloadedBytes = 1_000L,
            totalBytes = 1_000L,
            source = "Manual",
            startedAt = null,
            completedAt = 1L,
            errorMessage = null,
        )
        // Insert a pending row with no localPath.
        db.downloadQueries.upsert(
            episodeId = "ep-pending",
            state = "Queued",
            localPath = null,
            downloadedBytes = 0L,
            totalBytes = 0L,
            source = "Manual",
            startedAt = null,
            completedAt = null,
            errorMessage = null,
        )

        assertEquals(
            "file:///path/ep-done.mp3",
            repo.localUriFor("ep-done"),
            "completed row should yield a file:// URI built from localPath",
        )
        assertNull(
            repo.localUriFor("ep-pending"),
            "pending row without localPath must not be treated as playable",
        )
        assertNull(
            repo.localUriFor("ep-missing"),
            "unknown episode id should return null",
        )

        scope.cancel()
    }

    private class FakeDownloadEngine : DownloadEngineApi {
        // Never emits — the repository's init block subscribes but this test doesn't need
        // progress events to flow.
        override val events: SharedFlow<DownloadProgress> =
            MutableSharedFlow<DownloadProgress>().asSharedFlow()

        override fun enqueue(job: DownloadJob) = Unit

        override fun cancel(episodeId: String) = Unit

        override fun delete(episodeId: String) = Unit
    }
}

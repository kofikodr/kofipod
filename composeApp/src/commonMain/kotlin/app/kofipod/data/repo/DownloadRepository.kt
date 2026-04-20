// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.kofipod.db.Download
import app.kofipod.db.KofipodDatabase
import app.kofipod.db.SelectAllWithMeta
import app.kofipod.downloads.DownloadEngine
import app.kofipod.downloads.DownloadJob
import app.kofipod.downloads.DownloadProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.datetime.Clock

data class DownloadRow(
    val episodeId: String,
    val state: String,
    val localPath: String?,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val source: String,
    val startedAt: Long?,
    val completedAt: Long?,
    val errorMessage: String?,
    val episodeTitle: String?,
    val podcastId: String?,
    val podcastTitle: String?,
    val artworkUrl: String?,
)

private fun SelectAllWithMeta.toDownloadRow(): DownloadRow =
    DownloadRow(
        episodeId = episodeId,
        state = state,
        localPath = localPath,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
        source = source,
        startedAt = startedAt,
        completedAt = completedAt,
        errorMessage = errorMessage,
        episodeTitle = episodeTitle,
        podcastId = podcastId,
        podcastTitle = podcastTitle,
        artworkUrl = artworkUrl,
    )

class DownloadRepository(
    private val db: KofipodDatabase,
    private val engine: DownloadEngine,
    scope: CoroutineScope,
) {
    init {
        engine.events.onEach { p ->
            when (p.state) {
                DownloadProgress.State.Queued ->
                    db.downloadQueries.updateState("Queued", null, p.episodeId)
                DownloadProgress.State.Downloading -> {
                    db.downloadQueries.updateProgress(p.downloadedBytes, p.totalBytes, p.episodeId)
                    db.downloadQueries.updateState("Downloading", null, p.episodeId)
                }
                DownloadProgress.State.Paused ->
                    db.downloadQueries.updateState("Paused", null, p.episodeId)
                DownloadProgress.State.Completed ->
                    db.downloadQueries.markCompleted(
                        localPath = p.localPath,
                        downloadedBytes = p.downloadedBytes,
                        totalBytes = p.totalBytes,
                        completedAt = Clock.System.now().toEpochMilliseconds(),
                        episodeId = p.episodeId,
                    )
                DownloadProgress.State.Failed ->
                    db.downloadQueries.updateState("Failed", p.errorMessage, p.episodeId)
            }
        }.launchIn(scope)
    }

    fun all(): Flow<List<Download>> = db.downloadQueries.selectAll().asFlow().mapToList(Dispatchers.Default)

    /** URI for the completed local file for [episodeId], or null if not downloaded. */
    fun localUriFor(episodeId: String): String? {
        val path = db.downloadQueries.localPathFor(episodeId).executeAsOneOrNull()?.localPath ?: return null
        return "file://$path"
    }

    fun allWithMeta(): Flow<List<DownloadRow>> =
        db.downloadQueries.selectAllWithMeta()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toDownloadRow() } }

    fun enqueue(
        episodeId: String,
        url: String,
        fileName: String,
        source: DownloadJob.Source,
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        db.downloadQueries.upsert(
            episodeId = episodeId,
            state = "Queued",
            localPath = null,
            downloadedBytes = 0,
            totalBytes = 0,
            source = source.name,
            startedAt = now,
            completedAt = null,
            errorMessage = null,
        )
        engine.enqueue(DownloadJob(episodeId, url, fileName, source))
    }

    fun cancel(episodeId: String) {
        engine.cancel(episodeId)
        db.downloadQueries.updateState("Paused", null, episodeId)
    }

    fun delete(episodeId: String) {
        engine.delete(episodeId)
        db.downloadQueries.delete(episodeId)
    }

    fun evictUntilUnderCap(capBytes: Long) {
        var total: Long = db.downloadQueries.totalCompletedBytes().executeAsOne()
        if (total <= capBytes) return
        val victims = db.downloadQueries.selectAutoCompletedOldestFirst().executeAsList()
        for (v in victims) {
            delete(v.episodeId)
            total -= v.totalBytes
            if (total <= capBytes) break
        }
    }
}

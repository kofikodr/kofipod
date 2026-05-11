// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.repo

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.sqldelight.coroutines.mapToOneOrNull
import com.kofikodr.kofipod.db.Download
import com.kofikodr.kofipod.db.KofipodDatabase
import com.kofikodr.kofipod.db.SelectAllWithMeta
import com.kofikodr.kofipod.db.SelectCompletedWithMeta
import com.kofikodr.kofipod.downloads.DownloadEngineApi
import com.kofikodr.kofipod.downloads.DownloadJob
import com.kofikodr.kofipod.downloads.DownloadProgress
import com.kofikodr.kofipod.downloads.downloadFileName
import com.kofikodr.kofipod.network.NetworkMonitor
import com.kofikodr.kofipod.network.NetworkType
import com.kofikodr.kofipod.snippets.FileCheckerApi
import com.kofikodr.kofipod.ui.UiEvent
import com.kofikodr.kofipod.ui.UiEventBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

data class CompletedDownload(
    val episodeId: String,
    val localPath: String?,
    val completedAt: Long?,
    val episodeTitle: String,
    val podcastId: String,
    val podcastTitle: String,
    val artworkUrl: String,
)

private fun SelectCompletedWithMeta.toCompletedDownload(): CompletedDownload =
    CompletedDownload(
        episodeId = episodeId,
        localPath = localPath,
        completedAt = completedAt,
        episodeTitle = episodeTitle.orEmpty(),
        podcastId = podcastId.orEmpty(),
        podcastTitle = podcastTitle.orEmpty(),
        artworkUrl = artworkUrl.orEmpty(),
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
    private val engine: DownloadEngineApi,
    private val settings: SettingsRepository,
    private val network: NetworkMonitor,
    scope: CoroutineScope,
    private val telemetry: com.kofikodr.kofipod.diagnostics.Telemetry,
    private val fileChecker: FileCheckerApi,
    private val uiEvents: UiEventBus,
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
                DownloadProgress.State.Completed -> {
                    db.downloadQueries.markCompleted(
                        localPath = p.localPath,
                        downloadedBytes = p.downloadedBytes,
                        totalBytes = p.totalBytes,
                        completedAt = Clock.System.now().toEpochMilliseconds(),
                        episodeId = p.episodeId,
                    )
                    telemetry.track(com.kofikodr.kofipod.diagnostics.TelemetryEvent.EpisodeDownloaded)
                }
                DownloadProgress.State.Failed -> {
                    db.downloadQueries.updateState("Failed", p.errorMessage, p.episodeId)
                    uiEvents.emit(UiEvent.Snackbar(downloadFailedMessage(p.errorMessage)))
                }
            }
        }.launchIn(scope)

        // Flush deferred downloads whenever the user's settings or connectivity allow them.
        combine(settings.wifiOnly(), network.type) { wifiOnly, type -> canDownloadNow(wifiOnly, type) }
            .distinctUntilChanged()
            .onEach { allowed -> if (allowed) flushWaiting() }
            .launchIn(scope)
    }

    private fun canDownloadNow(
        wifiOnly: Boolean,
        type: NetworkType,
    ): Boolean =
        when (type) {
            NetworkType.None -> false
            NetworkType.Wifi -> true
            NetworkType.Metered -> !wifiOnly
        }

    private fun flushWaiting() {
        val waiting = db.downloadQueries.selectByState(STATE_WAITING_WIFI).executeAsList()
        for (row in waiting) {
            val ep = db.episodeQueries.selectById(row.episodeId).executeAsOneOrNull() ?: continue
            if (ep.enclosureUrl.isBlank()) continue
            val source = runCatching { DownloadJob.Source.valueOf(row.source) }.getOrDefault(DownloadJob.Source.Manual)
            db.downloadQueries.updateState("Queued", null, row.episodeId)
            engine.enqueue(
                DownloadJob(
                    episodeId = row.episodeId,
                    url = ep.enclosureUrl,
                    targetFileName = downloadFileName(row.episodeId, ep.enclosureMimeType),
                    source = source,
                ),
            )
        }
    }

    fun all(): Flow<List<Download>> = db.downloadQueries.selectAll().asFlow().mapToList(Dispatchers.Default)

    fun forEpisodeFlow(episodeId: String): Flow<Download?> =
        db.downloadQueries.selectByEpisode(episodeId).asFlow().mapToOneOrNull(Dispatchers.Default)

    /**
     * Raw filesystem path for the completed local file for [episodeId], or null.
     *
     * Self-heals against the bad-restore class of bug: a `Download` row can claim
     * `state='Completed'` with a `localPath`, while the actual audio file is gone
     * (external deletion, restored DB without restored files, OS storage pruning).
     * If the DB returns a path but the file isn't on disk, the orphaned row is
     * wiped here so the resolver falls through to the streaming URL instead of
     * handing ExoPlayer a dangling `file://` URI that stalls playback silently.
     */
    fun localPathFor(episodeId: String): String? {
        val path = db.downloadQueries.localPathFor(episodeId).executeAsOneOrNull()?.localPath ?: return null
        if (fileChecker.exists(path)) return path
        delete(episodeId)
        return null
    }

    /** Full [Download] row for [episodeId], or null when no row exists. */
    fun rowFor(episodeId: String): Download? = db.downloadQueries.selectByEpisode(episodeId).executeAsOneOrNull()

    /** Same as [localPathFor] but wrapped in a `file://` URI for passing to a URL-scheme consumer. */
    fun localUriFor(episodeId: String): String? = localPathFor(episodeId)?.let { "file://$it" }

    fun allWithMeta(): Flow<List<DownloadRow>> =
        db.downloadQueries.selectAllWithMeta()
            .asFlow()
            .mapToList(Dispatchers.Default)
            .map { rows -> rows.map { it.toDownloadRow() } }

    fun completedWithMetaNow(): List<CompletedDownload> =
        db.downloadQueries.selectCompletedWithMeta()
            .executeAsList()
            .map { it.toCompletedDownload() }

    /**
     * Returns a playable source URL for [episodeId], preferring a completed local download
     * (as a `file://` URI) over the remote [enclosureUrl]. Returns null when neither is
     * available.
     */
    fun resolvedSourceUrl(
        episodeId: String,
        enclosureUrl: String,
    ): String? {
        val local = localUriFor(episodeId)
        return when {
            local != null -> local
            enclosureUrl.isNotBlank() -> enclosureUrl
            else -> null
        }
    }

    fun enqueue(
        episodeId: String,
        url: String,
        fileName: String,
        source: DownloadJob.Source,
    ) {
        val now = Clock.System.now().toEpochMilliseconds()
        val allowed = canDownloadNow(settings.wifiOnlyNow(), network.type.value)
        db.downloadQueries.upsert(
            episodeId = episodeId,
            state = if (allowed) "Queued" else STATE_WAITING_WIFI,
            localPath = null,
            downloadedBytes = 0,
            totalBytes = 0,
            source = source.name,
            startedAt = now,
            completedAt = null,
            errorMessage = null,
        )
        when {
            allowed ->
                engine.enqueue(DownloadJob(episodeId, url, fileName, source))
            // If the gate opened between our first read and the DB commit, a concurrent
            // flushWaiting() may have scanned WaitingForWifi rows before ours landed and
            // missed it. Re-check and promote inline to avoid orphaning the row.
            canDownloadNow(settings.wifiOnlyNow(), network.type.value) -> {
                db.downloadQueries.updateState("Queued", null, episodeId)
                engine.enqueue(DownloadJob(episodeId, url, fileName, source))
            }
        }
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

    /** Convenience for callers that don't already have the cap in hand. */
    fun evictUntilUnderCap() = evictUntilUnderCap(settings.storageCapBytesNow())

    companion object {
        /** Persistent state for downloads deferred until the Wi-Fi / metered rule allows them. */
        const val STATE_WAITING_WIFI = "WaitingForWifi"

        // Cap appended detail at a sane length for a snackbar — raw errors can be long
        // stack-trace messages from the engine, which would overflow a one-line toast.
        // Over-length messages are truncated (not dropped) so callers still see the
        // start of an "HTTP 404"-style status before the ellipsis.
        private const val SNACKBAR_DETAIL_MAX_LEN = 60

        internal fun downloadFailedMessage(errorMessage: String?): String {
            val trimmed = errorMessage?.trim()?.takeIf { it.isNotEmpty() } ?: return "Download failed"
            val detail =
                if (trimmed.length > SNACKBAR_DETAIL_MAX_LEN) {
                    trimmed.take(SNACKBAR_DETAIL_MAX_LEN) + "…"
                } else {
                    trimmed
                }
            return "Download failed: $detail"
        }
    }
}

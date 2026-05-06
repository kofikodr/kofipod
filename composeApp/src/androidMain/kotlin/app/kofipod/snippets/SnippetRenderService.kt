// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.kofipod.R
import app.kofipod.data.repo.DownloadRepository
import app.kofipod.data.repo.EpisodeSource
import app.kofipod.share.Sharer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.io.File

/**
 * One-shot foreground service that renders a single Snippet to disk and
 * triggers the system share sheet via the in-app Sharer when done.
 *
 * Single-render-at-a-time. A new enqueue cancels the in-flight render before
 * starting the new one. Multiple back-to-back enqueues will see the earlier
 * render cancelled — the user's most recent trim/save wins.
 *
 * FG type is `mediaProcessing` on API 35+ (matches the AndroidManifest entry);
 * API 29-34 falls back to `dataSync` (also declared in the manifest); pre-Q
 * uses the untyped `startForeground` overload.
 */
class SnippetRenderService : Service() {
    private val repo: SnippetRepository by inject()
    private val episodes: EpisodeSource by inject()
    private val downloads: DownloadRepository by inject()
    private val resolver: SnippetSourceResolver by inject()
    private val exporter: SnippetExporter by inject()
    private val sharer: Sharer by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val snippetId =
            intent?.getStringExtra(EXTRA_SNIPPET_ID)
                ?: run {
                    stopSelf(startId)
                    return START_NOT_STICKY
                }

        startForegroundCompat()
        // Cancel any in-flight render before queuing the new one. New requests
        // win — the user just trimmed/saved this snippet, they want THIS render.
        currentJob?.cancel()
        currentJob =
            scope.launch {
                try {
                    renderOne(snippetId)
                } finally {
                    stopSelf(startId)
                }
            }
        return START_REDELIVER_INTENT
    }

    private suspend fun renderOne(snippetId: String) {
        val snippet = repo.selectById(snippetId) ?: return
        // EpisodeSource exposes only flow-based episode lookup on the interface;
        // grab the current emission and move on.
        val episode = episodes.episodeFlow(snippet.episodeId).firstOrNull() ?: return

        // DownloadRepository.localPathFor() is the synchronous one-shot we want —
        // we don't need to subscribe to download progress, we just need the current
        // local path (if any) for resolver to decide local vs remote.
        val localPath = downloads.localPathFor(snippet.episodeId)
        val source =
            resolver.resolve(
                localPath = localPath,
                enclosureUrl = episode.enclosureUrl,
            )
        val sourceUriOrPath =
            when (source) {
                is SnippetSource.Local -> source.path
                is SnippetSource.Remote -> source.url
                SnippetSource.None -> return
            }

        val outputDir = File(cacheDir, "snippets").apply { mkdirs() }
        val outputFile = File(outputDir, "${snippet.id}.${SnippetFormat.MP3.fileExtension}")

        val result =
            exporter.exportMp3(
                snippet = snippet,
                sourceUriOrPath = sourceUriOrPath,
                outputPath = outputFile.absolutePath,
                onProgress = { p -> updateProgressNotification(p) },
            )

        result.fold(
            onSuccess = { path ->
                repo.setRendered(snippet.id, SnippetFormat.MP3, path)
                triggerShare(snippet, path)
            },
            onFailure = {
                // TODO Slice 4: surface error toast via UiEventBus
            },
        )
    }

    private fun triggerShare(
        snippet: Snippet,
        path: String,
    ) {
        val episodeUrl =
            "https://podcastindex.org/podcast/${snippet.podcastId}?episode=${snippet.episodeId}"
        sharer.shareFile(
            title = snippet.title ?: "Snippet",
            path = path,
            mimeType = SnippetFormat.MP3.mimeType,
            captionText = "${snippet.title ?: "Snippet"}\n$episodeUrl",
        )
    }

    private fun startForegroundCompat() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Snippet rendering",
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val notif = buildProgressNotification(progress = 0f).build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10+ requires a declared type; the manifest declares
            // mediaProcessing|dataSync, so dataSync is the valid fallback for
            // API 29-34 (FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING is API 35+).
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun updateProgressNotification(progress: Float) {
        val notif = buildProgressNotification(progress).build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    private fun buildProgressNotification(progress: Float): NotificationCompat.Builder {
        val pct = (progress * 100).toInt().coerceIn(0, 100)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rendering snippet")
            .setContentText("$pct%")
            .setProgress(100, pct, progress <= 0f)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
    }

    override fun onDestroy() {
        currentJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SNIPPET_ID = "app.kofipod.extra.SNIPPET_ID"
        private const val CHANNEL_ID = "snippet_render"
        private const val NOTIF_ID = 0x517A1
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.kofikodr.kofipod.R
import com.kofikodr.kofipod.data.repo.DownloadRepository
import com.kofikodr.kofipod.data.repo.EpisodeSource
import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.share.Sharer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * One-shot foreground service that renders a single Snippet to disk and
 * triggers the system share sheet via the in-app Sharer when done.
 *
 * Single-render-at-a-time. A new enqueue cancels the in-flight render before
 * starting the new one. Multiple back-to-back enqueues will see the earlier
 * render cancelled — the user's most recent trim/save wins.
 *
 * When a new [onStartCommand] arrives for a DIFFERENT snippet, a
 * [RenderProgress.Failed] is published for the displaced snippet before
 * cancellation, so any editor observing that snippet's progress doesn't
 * get stuck in "Rendering…" forever.
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
    private val captions: SnippetCaptionRepository by inject()
    private val waveforms: WaveformGenerator by inject()
    private val library: LibraryRepository by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var currentJob: Job? = null

    // Tracks the snippet currently being rendered. AtomicReference makes the
    // read-modify-write in onStartCommand atomic (getAndSet) and the clear in
    // renderOne's finally block TOCTOU-safe (compareAndSet). @Volatile alone
    // only guarantees visibility, not atomicity on compound operations.
    private val currentSnippetId = AtomicReference<String?>(null)

    // Tracks the format of the in-progress render for notification copy.
    // Defaults to MP4 (the design's headline format) until the first render
    // resolves the actual format from the snippet.
    @Volatile private var currentFormat: SnippetFormat = SnippetFormat.MP4

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

        // Atomic read-and-replace: prev is the previous value; the new value
        // is now in place. If there was a different snippet rendering, publish
        // Failed for the displaced one so any observer (e.g. the editor screen)
        // doesn't stay stuck in "Rendering…" forever.
        val prev = currentSnippetId.getAndSet(snippetId)
        if (prev != null && prev != snippetId) {
            SnippetRenderProgressBus.publish(
                RenderProgress.Failed(prev, "Replaced by newer render"),
            )
        }

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
        try {
            val snippet =
                repo.selectById(snippetId) ?: run {
                    SnippetRenderProgressBus.publish(RenderProgress.Failed(snippetId, "Snippet not found"))
                    return
                }
            // EpisodeSource exposes only flow-based episode lookup on the interface;
            // grab the current emission and move on.
            val episode =
                episodes.episodeFlow(snippet.episodeId).firstOrNull() ?: run {
                    SnippetRenderProgressBus.publish(RenderProgress.Failed(snippetId, "Episode not found"))
                    return
                }

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
                    SnippetSource.None -> {
                        SnippetRenderProgressBus.publish(RenderProgress.Failed(snippetId, "Audio unavailable"))
                        return
                    }
                }

            // For the first render of a fresh snippet lastExportFormat is null,
            // so we default to MP4 (the design's headline format). When Task 12
            // lands markFormatPending, the editor will pre-persist the chosen format
            // and this default becomes a fallback only.
            val format = snippet.lastExportFormat ?: SnippetFormat.MP4
            currentFormat = format

            val outputDir = File(cacheDir, "snippets").apply { mkdirs() }
            val outputFile = File(outputDir, "${snippet.id}.${format.fileExtension}")

            SnippetRenderProgressBus.publish(RenderProgress.InFlight(snippetId, fraction = 0f))

            val result =
                when (format) {
                    SnippetFormat.MP3 ->
                        exporter.exportMp3(
                            snippet = snippet,
                            sourceUriOrPath = sourceUriOrPath,
                            outputPath = outputFile.absolutePath,
                            onProgress = { f ->
                                SnippetRenderProgressBus.publish(RenderProgress.InFlight(snippetId, f))
                                updateProgressNotification(f, format)
                            },
                        )

                    SnippetFormat.MP4 -> {
                        // Resolve caption: honour any author override first, then fall
                        // back to the caption repository (transcript or Gemini path).
                        val captionText =
                            snippet.captionOverride
                                ?: when (val r = captions.resolveFor(snippet)) {
                                    is CaptionResolution.FromTranscript -> r.text
                                    is CaptionResolution.FromGemini -> r.text
                                    is CaptionResolution.None -> null
                                }
                        // Cover art comes from the subscribed podcast row — always
                        // present for subscribed podcasts; null-safe if somehow absent.
                        val coverArtUrl = library.podcastNow(snippet.podcastId)?.artworkUrl
                        // Waveform is deterministic on snippet.id so editor preview
                        // and final render always agree on the bar shape.
                        val waveform = waveforms.generate(seed = snippet.id)
                        exporter.exportMp4(
                            snippet = snippet,
                            sourceUriOrPath = sourceUriOrPath,
                            outputPath = outputFile.absolutePath,
                            coverArtUriOrPath = coverArtUrl,
                            captionText = captionText,
                            waveformSamples = waveform,
                            onProgress = { f ->
                                SnippetRenderProgressBus.publish(RenderProgress.InFlight(snippetId, f))
                                updateProgressNotification(f, format)
                            },
                        )
                    }
                }

            result.fold(
                onSuccess = { path ->
                    repo.setRendered(snippet.id, format, path)
                    SnippetRenderProgressBus.publish(RenderProgress.Complete(snippetId, path, format))
                    triggerShare(snippet, path, format)
                },
                onFailure = { t ->
                    SnippetRenderProgressBus.publish(
                        RenderProgress.Failed(snippetId, t.message ?: "Render failed"),
                    )
                },
            )
        } finally {
            // Clear only if no newer enqueue has already taken over the slot —
            // compareAndSet is a no-op when another snippetId has swapped in.
            currentSnippetId.compareAndSet(snippetId, null)
        }
    }

    private fun triggerShare(
        snippet: Snippet,
        path: String,
        format: SnippetFormat,
    ) {
        val episodeUrl =
            "https://podcastindex.org/podcast/${snippet.podcastId}?episode=${snippet.episodeId}"
        sharer.shareFile(
            title = snippet.title ?: "Snippet",
            path = path,
            mimeType = format.mimeType,
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

    private fun updateProgressNotification(
        progress: Float,
        format: SnippetFormat,
    ) {
        val notif = buildProgressNotification(progress, format).build()
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notif)
    }

    /**
     * Builds the render-progress notification. [format] defaults to [currentFormat]
     * so the [startForegroundCompat] call site (which runs before the format is
     * resolved from the snippet) can omit the parameter; the first
     * [updateProgressNotification] call will refresh with the actual format.
     */
    private fun buildProgressNotification(
        progress: Float,
        format: SnippetFormat = currentFormat,
    ): NotificationCompat.Builder {
        val pct = (progress * 100).toInt().coerceIn(0, 100)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Rendering snippet")
            .setContentText("${format.name} · $pct%")
            .setProgress(100, pct, progress <= 0f)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
    }

    override fun onDestroy() {
        currentSnippetId.set(null)
        currentJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_SNIPPET_ID = "com.kofikodr.kofipod.extra.SNIPPET_ID"
        private const val CHANNEL_ID = "snippet_render"
        private const val NOTIF_ID = 0x517A1
    }
}

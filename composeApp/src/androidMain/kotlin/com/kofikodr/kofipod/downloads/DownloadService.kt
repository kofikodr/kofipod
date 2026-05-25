// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

class DownloadService : Service() {
    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val active = ConcurrentHashMap<String, Job>()

    // Retained so ACTION_CANCEL can abort a *stalled* socket read — coroutine
    // cancellation alone can't interrupt a thread blocked in OkHttp's read().
    private val calls = ConcurrentHashMap<String, Call>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        startForegroundIfNeeded()
        val action = intent?.action ?: return START_NOT_STICKY
        when (action) {
            ACTION_ENQUEUE -> {
                val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return START_NOT_STICKY
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_FILENAME) ?: episodeId
                DownloadBroadcaster.tryEmit(
                    DownloadProgress(episodeId, 0, 0, DownloadProgress.State.Queued),
                )
                // Lazy-start so the Job is registered in `active` *before* the body can run.
                // Otherwise a coroutine that finishes before the map assignment could leave a
                // stale completed entry behind (stopIfIdle would never fire).
                val job =
                    scope.launch(start = CoroutineStart.LAZY) {
                        try {
                            downloadWithResume(episodeId, url, name)
                        } catch (e: CancellationException) {
                            // User cancelled — ACTION_CANCEL already emitted Paused.
                            // Propagate so the coroutine completes as cancelled.
                            throw e
                        } catch (e: Throwable) {
                            if (isActive) {
                                DownloadBroadcaster.emit(
                                    DownloadProgress(
                                        episodeId,
                                        0,
                                        0,
                                        DownloadProgress.State.Failed,
                                        e.message,
                                    ),
                                )
                            } else {
                                // Cancelled mid blocking-read: Call.cancel() surfaces as an
                                // IOException, not CancellationException. Treat it as a pause,
                                // never a failure. tryEmit because the coroutine is cancelling.
                                DownloadBroadcaster.tryEmit(
                                    DownloadProgress(episodeId, 0, 0, DownloadProgress.State.Paused),
                                )
                            }
                        } finally {
                            // Value-conditional remove so a fast cancel+retry for the same
                            // episode isn't clobbered by this (older) coroutine's cleanup.
                            active.remove(episodeId, coroutineContext[Job])
                            stopIfIdle()
                        }
                    }
                active[episodeId] = job
                job.start()
            }
            ACTION_CANCEL -> {
                val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return START_NOT_STICKY
                // Cancel the Job *before* aborting the call so the IOException from the
                // aborted read is observed as a cancel (isActive == false), not a failure.
                active.remove(episodeId)?.cancel()
                calls.remove(episodeId)?.cancel()
                DownloadBroadcaster.tryEmit(
                    DownloadProgress(episodeId, 0, 0, DownloadProgress.State.Paused),
                )
                stopIfIdle()
            }
        }
        return START_STICKY
    }

    private fun stopIfIdle() {
        if (active.isEmpty()) stopSelf()
    }

    private suspend fun downloadWithResume(
        episodeId: String,
        url: String,
        name: String,
    ) {
        val file = File(filesDir, "downloads/$name").apply { parentFile?.mkdirs() }
        val existing = if (file.exists()) file.length() else 0L
        val sentRange = existing > 0
        val request =
            Request.Builder().url(url).apply {
                if (sentRange) addHeader("Range", "bytes=$existing-")
            }.build()
        // Bail before the blocking execute() if we were already cancelled — the Call isn't
        // registered yet, so ACTION_CANCEL's calls.remove() couldn't have aborted it.
        currentCoroutineContext().ensureActive()
        val call = client.newCall(request)
        calls[episodeId] = call
        try {
            call.execute().use { resp ->
                when (val plan = resumePlan(existingBytes = existing, sentRangeRequest = sentRange, responseCode = resp.code)) {
                    is ResumePlan.Fail -> {
                        DownloadBroadcaster.emit(
                            DownloadProgress(
                                episodeId,
                                existing,
                                existing,
                                DownloadProgress.State.Failed,
                                "HTTP ${plan.httpCode}",
                            ),
                        )
                        return
                    }
                    is ResumePlan.Overwrite -> writeBody(episodeId, file, resp, startOffset = 0L, appendToExisting = false)
                    is ResumePlan.Append -> writeBody(episodeId, file, resp, startOffset = plan.from, appendToExisting = true)
                }
                // Don't report Completed if a cancel landed right as the stream finished.
                currentCoroutineContext().ensureActive()
                DownloadBroadcaster.emit(
                    DownloadProgress(
                        episodeId = episodeId,
                        downloadedBytes = file.length(),
                        totalBytes = file.length(),
                        state = DownloadProgress.State.Completed,
                        localPath = file.absolutePath,
                    ),
                )
            }
        } finally {
            // Conditional remove: only drop the entry if it's still *our* Call, so a retry
            // that re-registered under the same episodeId keeps its (cancellable) Call.
            calls.remove(episodeId, call)
        }
    }

    /**
     * Pull the response body into [file]. [startOffset] is the byte we should report
     * progress from (the bytes already on disk if appending, else 0). [appendToExisting]
     * is FileOutputStream's append flag — must be `false` when the server sent the
     * whole file (e.g., ignored our Range header), or the prefix would be duplicated.
     */
    private suspend fun writeBody(
        episodeId: String,
        file: File,
        resp: Response,
        startOffset: Long,
        appendToExisting: Boolean,
    ) {
        // Content-Length is the body size; for a 206 it's only the partial. Add the
        // already-on-disk prefix to get a UI total. Unknown ⇒ best-effort -1L.
        val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
        val total = if (contentLength > 0) contentLength + startOffset else -1L
        resp.body?.byteStream()?.use { stream ->
            FileOutputStream(file, appendToExisting).use { out ->
                copyWithProgress(
                    episodeId = episodeId,
                    input = stream,
                    output = out,
                    startOffset = startOffset,
                    total = total,
                    emit = { DownloadBroadcaster.emit(it) },
                )
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                mgr.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "Downloads",
                        NotificationManager.IMPORTANCE_LOW,
                    ),
                )
            }
        }
    }

    private fun startForegroundIfNeeded() {
        val notif =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.kofikodr.kofipod.R.drawable.ic_notification)
                .setContentTitle("Downloading episodes")
                .setOngoing(true)
                .build()
        startForeground(NOTIF_ID, notif)
    }

    override fun onDestroy() {
        // Cancelling the scope alone can't interrupt a thread blocked in OkHttp's read();
        // abort the calls explicitly so no socket/IO thread leaks past teardown.
        calls.values.forEach { it.cancel() }
        calls.clear()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_ENQUEUE = "kofipod.action.ENQUEUE"
        const val ACTION_CANCEL = "kofipod.action.CANCEL"
        const val EXTRA_EPISODE_ID = "ep"
        const val EXTRA_URL = "url"
        const val EXTRA_FILENAME = "filename"
        private const val CHANNEL_ID = "kofipod.downloads"
        private const val NOTIF_ID = 77
    }
}

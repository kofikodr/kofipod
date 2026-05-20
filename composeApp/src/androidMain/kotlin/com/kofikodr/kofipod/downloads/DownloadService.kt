// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
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
                active[episodeId] =
                    scope.launch {
                        runCatching { downloadWithResume(episodeId, url, name) }
                            .onFailure {
                                DownloadBroadcaster.emit(
                                    DownloadProgress(
                                        episodeId,
                                        0,
                                        0,
                                        DownloadProgress.State.Failed,
                                        it.message,
                                    ),
                                )
                            }
                        active.remove(episodeId)
                        stopIfIdle()
                    }
            }
            ACTION_CANCEL -> {
                val episodeId = intent.getStringExtra(EXTRA_EPISODE_ID) ?: return START_NOT_STICKY
                active.remove(episodeId)?.cancel()
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
        client.newCall(request).execute().use { resp ->
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
                val buf = ByteArray(64 * 1024)
                var read: Int
                var received = startOffset
                var lastEmit = 0L
                while (stream.read(buf).also { read = it } > 0) {
                    out.write(buf, 0, read)
                    received += read
                    val now = System.currentTimeMillis()
                    if (now - lastEmit > 200) {
                        DownloadBroadcaster.emit(
                            DownloadProgress(
                                episodeId,
                                received,
                                total.coerceAtLeast(received),
                                DownloadProgress.State.Downloading,
                            ),
                        )
                        lastEmit = now
                    }
                }
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

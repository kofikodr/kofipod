// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.downloads

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
        val request =
            Request.Builder().url(url).apply {
                if (existing > 0) addHeader("Range", "bytes=$existing-")
            }.build()
        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                DownloadBroadcaster.emit(
                    DownloadProgress(
                        episodeId,
                        existing,
                        existing,
                        DownloadProgress.State.Failed,
                        "HTTP ${resp.code}",
                    ),
                )
                return
            }
            val contentLength = resp.header("Content-Length")?.toLongOrNull() ?: -1L
            val total = if (contentLength > 0) contentLength + existing else -1L
            resp.body?.byteStream()?.use { stream ->
                FileOutputStream(file, existing > 0).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read: Int
                    var received = existing
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
            DownloadBroadcaster.emit(
                DownloadProgress(
                    episodeId,
                    file.length(),
                    file.length(),
                    DownloadProgress.State.Completed,
                ),
            )
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
                .setSmallIcon(android.R.drawable.stat_sys_download)
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

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.downloads

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

actual class DownloadEngine(private val context: Context) {
    actual val events: SharedFlow<DownloadProgress> = DownloadBroadcaster.events

    actual fun enqueue(job: DownloadJob) {
        val intent =
            Intent(context, DownloadService::class.java).apply {
                action = DownloadService.ACTION_ENQUEUE
                putExtra(DownloadService.EXTRA_EPISODE_ID, job.episodeId)
                putExtra(DownloadService.EXTRA_URL, job.url)
                putExtra(DownloadService.EXTRA_FILENAME, job.targetFileName)
            }
        startService(intent)
    }

    actual fun cancel(episodeId: String) {
        val intent =
            Intent(context, DownloadService::class.java).apply {
                action = DownloadService.ACTION_CANCEL
                putExtra(DownloadService.EXTRA_EPISODE_ID, episodeId)
            }
        startService(intent)
    }

    actual fun delete(episodeId: String) {
        val dir = File(context.filesDir, "downloads")
        dir.listFiles()
            ?.filter { it.name.startsWith(episodeId) }
            ?.forEach { it.delete() }
    }

    private fun startService(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}

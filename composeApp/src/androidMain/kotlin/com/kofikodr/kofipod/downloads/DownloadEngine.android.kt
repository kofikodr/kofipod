// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.downloads

import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

actual class DownloadEngine(private val context: Context) : DownloadEngineApi {
    actual override val events: SharedFlow<DownloadProgress> = DownloadBroadcaster.events

    actual override fun enqueue(job: DownloadJob) {
        val intent =
            Intent(context, DownloadService::class.java).apply {
                action = DownloadService.ACTION_ENQUEUE
                putExtra(DownloadService.EXTRA_EPISODE_ID, job.episodeId)
                putExtra(DownloadService.EXTRA_URL, job.url)
                putExtra(DownloadService.EXTRA_FILENAME, job.targetFileName)
            }
        startService(intent)
    }

    actual override fun cancel(episodeId: String) {
        val intent =
            Intent(context, DownloadService::class.java).apply {
                action = DownloadService.ACTION_CANCEL
                putExtra(DownloadService.EXTRA_EPISODE_ID, episodeId)
            }
        startService(intent)
    }

    actual override fun delete(episodeId: String) {
        // Match by the sanitised stem the writer produced — `episodeId` may contain
        // characters that `downloadFileName` remaps, in which case a raw
        // `name.startsWith(episodeId)` would never match and leak the file. Also use
        // exact-stem equality rather than `startsWith` so id `123` doesn't also
        // delete the file for id `12345`.
        val expectedStem = downloadFileStem(episodeId)
        val dir = File(context.filesDir, "downloads")
        dir.listFiles()
            ?.filter { it.nameWithoutExtension == expectedStem }
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

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Duration

/**
 * Android production binding for [PkmExportScheduler]. Enqueues a one-shot
 * [PkmExportWorker] run, deduped by [UNIQUE_NAME].
 *
 * `KEEP` rather than `APPEND_OR_REPLACE`: a single queued worker is enough
 * to drain however many rows are queued, because the worker reads its
 * inputs from `ExportLog` rather than from work data. Back-to-back failure
 * calls (e.g. a multi-export that hits a network blip mid-flight) should
 * not cancel and re-queue an already-pending worker. Mirrors the
 * [AndroidAiSummaryScheduler] pattern.
 *
 * `NetworkType.CONNECTED` matches the actual transport requirement for
 * connection-bound sinks (Readwise HTTPS, Notion HTTPS). Obsidian writes
 * to the local SAF folder and would technically work offline — but
 * gating the whole worker on connectivity is cheaper than per-sink
 * branching, and an offline Obsidian queue can wait the few seconds it
 * takes for the radio to come back.
 */
class AndroidPkmExportScheduler(private val context: Context) : PkmExportScheduler {
    override fun enqueue() {
        val request =
            OneTimeWorkRequestBuilder<PkmExportWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, Duration.ofSeconds(BACKOFF_SECONDS))
                .addTag(TAG)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    companion object {
        const val UNIQUE_NAME = "pkm_export_retry"
        const val TAG = "pkm_export_retry"
        private const val BACKOFF_SECONDS = 30L
    }
}

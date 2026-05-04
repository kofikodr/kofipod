// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Android production binding for [AiSummaryScheduler]. Enqueues a one-shot
 * [AiSummaryWorker] run, deduped by [UNIQUE_NAME].
 *
 * `KEEP` rather than `REPLACE`: tapping Generate three times in quick succession
 * should not cancel and re-queue the worker on each tap. The appScope launch is
 * the primary execution path; the worker is the process-death backstop, and
 * one queued worker is enough to drain however many markers are pending.
 *
 * `NetworkType.CONNECTED` covers metered + unmetered. The user explicitly opted
 * in to summarisation, so we don't gate on Wi-Fi the way downloads do — a 60s
 * call is well within most metered budgets.
 */
class AndroidAiSummaryScheduler(private val context: Context) : AiSummaryScheduler {
    override fun enqueueResume() {
        val req =
            OneTimeWorkRequestBuilder<AiSummaryWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .addTag(TAG)
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, req)
    }

    companion object {
        const val UNIQUE_NAME = "ai_summary_resume"
        const val TAG = "ai_summary_resume"
    }
}

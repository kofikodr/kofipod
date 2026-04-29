// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.kofipod.ai.AiSummaryRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Drains [app.kofipod.db.PendingAiSummary] markers — the AI pipeline's
 * resume-after-process-death backstop. Enqueued by [AiSummaryScheduler]
 * every time the user taps Generate, so an OS process kill during a 58 MB
 * audio upload can be recovered without the user re-tapping on next launch.
 *
 * Always returns [Result.success]. The pipeline's surfaced errors are
 * user-facing (Error card with explicit Retry); auto-retrying through the
 * worker would burn quota silently. A genuine worker-side crash falls
 * through to [Result.retry] via WorkManager's default exception handling.
 */
class AiSummaryWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val repo: AiSummaryRepository by inject()

    override suspend fun doWork(): Result =
        runCatching {
            // resumePending() is single-flight per episode and joins any
            // appScope job already running for the same id, so a worker
            // running while the foreground pipeline is still alive is a
            // cheap no-op rather than a duplicate request.
            //
            // Note: a worker fire that hits an active appScope job will
            // short-circuit through the in-flight guard and return success
            // even though the marker is still in the DB. That's expected —
            // the appScope job's `finally` block owns the delete, and the
            // KEEP policy on [AiSummaryScheduler] caps the queued worker
            // count at one regardless of how many enqueue calls fired.
            repo.resumePending()
            Result.success()
        }.getOrElse { Result.retry() }
}

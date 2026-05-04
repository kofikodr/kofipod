// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.kofipod.ai.AiSummaryRepository
import app.kofipod.ai.DiscussRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Drains [app.kofipod.db.PendingAiOperation] markers — the AI pipelines'
 * resume-after-process-death backstop. Enqueued by [AiSummaryScheduler]
 * every time the user taps Generate, so an OS process kill during a 58 MB
 * audio upload can be recovered without the user re-tapping on next launch.
 *
 * Two consumers, one worker (per the generalised marker table):
 *  - [AiSummaryRepository.resumePending] re-fires the full Summary pipeline
 *    for `kind = 'summary'` rows. Single-flight per episode, joins any
 *    appScope job already running.
 *  - [DiscussRepository.cleanStaleDiscussUploads] just deletes `kind =
 *    'discuss_upload'` rows. Recovery for a chat send is intentionally
 *    user-driven (re-tap send) — re-firing minutes later, with the user no
 *    longer watching the screen, would be jarring.
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
    private val summaryRepo: AiSummaryRepository by inject()
    private val discussRepo: DiscussRepository by inject()

    override suspend fun doWork(): Result {
        // Wrap each consumer independently so a thrown exception in one
        // doesn't prevent the other from running. resumePending() catches
        // its own AiError surface internally, but a corrupt-DB or driver-
        // level throw could escape — without this split, the discuss
        // marker sweep would be skipped and stale rows would accumulate
        // until the next clean worker fire.
        val summaryFailed = runCatching { summaryRepo.resumePending() }.isFailure
        runCatching { discussRepo.cleanStaleDiscussUploads() }
        // Only retry when the summary side actually threw — discuss
        // cleanup is idempotent and a failure there isn't worth re-running
        // the (potentially expensive) summary resume for.
        return if (summaryFailed) Result.retry() else Result.success()
    }
}

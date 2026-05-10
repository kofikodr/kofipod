// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.kofipod.pkm.PkmExportCoordinator
import app.kofipod.pkm.connections.ExportLogRepository
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Drains [app.kofipod.pkm.connections.ExportLogEntry] rows in `status='queued'`
 * by replaying each through [PkmExportCoordinator.retry]. Enqueued by
 * [AndroidPkmExportScheduler] every time the coordinator records a transient
 * failure, so a network blip during a Readwise POST can be recovered without
 * the user re-tapping Export.
 *
 * Only `queued` rows are retried. `failed` rows represent permanent failures
 * (a [app.kofipod.pkm.sinks.ExportSinkResult.PermanentFailure] from the sink)
 * and remain user-driven — the user must explicitly retry from the Export
 * action sheet, since auto-retrying e.g. a 401 from Readwise would burn the
 * worker budget without ever succeeding.
 *
 * Uses [Result.retry] only when [PkmExportCoordinator.retry] itself throws —
 * the coordinator's own `executeInternal` already maps transient failures to
 * `markQueued` (so the row is picked up on the next worker fire) and
 * permanent failures to `markFailed` (so the row is skipped here on the
 * next fire). A bare throw escaping `runCatching` therefore means a
 * driver-level or process-level fault, which warrants a WorkManager retry.
 */
class PkmExportWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val coordinator: PkmExportCoordinator by inject()
    private val exportLog: ExportLogRepository by inject()

    override suspend fun doWork(): Result {
        val pending = exportLog.selectQueuedOrFailed()
        if (pending.isEmpty()) return Result.success()
        var anyThrew = false
        for (entry in pending) {
            if (entry.status != STATUS_QUEUED) continue
            try {
                coordinator.retry(entry)
            } catch (e: CancellationException) {
                // Propagate WorkManager cancellation up the coroutine tree.
                // stdlib runCatching swallows CancellationException, which would
                // mask cancel signals and let late writes race the next worker.
                throw e
            } catch (_: Throwable) {
                anyThrew = true
            }
        }
        return if (anyThrew) Result.retry() else Result.success()
    }

    private companion object {
        const val STATUS_QUEUED = "queued"
    }
}

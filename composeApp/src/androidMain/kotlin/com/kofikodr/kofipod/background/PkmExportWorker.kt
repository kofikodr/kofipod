// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kofikodr.kofipod.pkm.PkmExportCoordinator
import com.kofikodr.kofipod.pkm.connections.ExportLogRepository
import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.pro.ProEntitlementRepository
import kotlinx.coroutines.CancellationException
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Drains [com.kofikodr.kofipod.pkm.connections.ExportLogEntry] rows in `status='queued'`
 * by replaying each through [PkmExportCoordinator.retry]. Enqueued by
 * [AndroidPkmExportScheduler] every time the coordinator records a transient
 * failure, so a network blip during a Readwise POST can be recovered without
 * the user re-tapping Export.
 *
 * Only `queued` rows are retried. `failed` rows represent permanent failures
 * (a [com.kofikodr.kofipod.pkm.sinks.ExportSinkResult.PermanentFailure] from the sink)
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
    private val entitlement: ProEntitlementRepository by inject()

    override suspend fun doWork(): Result {
        // Only `queued` rows are retryable here (`failed` are permanent and user-driven).
        // Filter BEFORE the empty-check so a table holding only stale `failed` rows
        // short-circuits without paying for the entitlement billing query below.
        val pending = exportLog.selectQueuedOrFailed().filter { it.status == STATUS_QUEUED }
        if (pending.isEmpty()) return Result.success()
        // Re-validate Pro before draining. PkmExportCoordinator intentionally does NOT
        // gate entitlement — the live Export path is gated by the calling ViewModel. The
        // worker is the one caller that bypasses that gate, so a user who queued an export
        // while Pro and then lost Pro (e.g. a refund) would otherwise have it silently
        // completed here (issue #24). refreshOnStart does a fresh billing query, falling
        // back to the cached entitlement when offline. Not-Pro leaves the rows queued (no
        // Result.retry, so we don't burn the worker budget); they drain if Pro returns.
        entitlement.refreshOnStart()
        if (!entitlementAllowsExportDrain(entitlement.state.value)) return Result.success()
        var anyThrew = false
        for (entry in pending) {
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

/**
 * Whether queued PKM exports may drain on the worker (background) path. Only a confirmed
 * [ProEntitlement.Pro] qualifies. [ProEntitlement.Unknown] — the state when the billing
 * query failed or the device is offline — and [ProEntitlement.Free] must NOT drain,
 * matching the "treat Unknown as Free for entitlement gating" rule the paywall uses: when
 * in doubt, withhold the Pro-only export rather than complete it for a possibly-lapsed user.
 *
 * Extracted as a pure function so the entitlement policy is unit-testable without standing
 * up WorkManager + Koin (which the project does not test directly).
 */
internal fun entitlementAllowsExportDrain(entitlement: ProEntitlement): Boolean = entitlement is ProEntitlement.Pro

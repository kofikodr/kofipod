// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kofikodr.kofipod.backup.BackupController
import com.kofikodr.kofipod.backup.BackupFolderStore
import com.kofikodr.kofipod.data.repo.SettingsRepository
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Periodic SAF-backup worker. Mirrors the shape of
 * [com.kofikodr.kofipod.background.EpisodeCheckWorker]: a thin shell over the same
 * [BackupController] entry point the manual button calls. Single-flight is enforced
 * by the controller, so a manual run + worker tick concurrent fire still produces
 * exactly one write.
 *
 * Two gates make this a no-op:
 *  - No backup folder URI configured — user hasn't picked one yet.
 *  - The Scheduler Details "daily check" toggle is off — user explicitly paused
 *    all scheduled work, which includes the SAF auto-backup. Manual "Back up now"
 *    is unaffected; only the automated path is suppressed.
 *
 * Either way the scheduler stays registered; the next tick re-evaluates the gates,
 * so flipping daily-check back on or picking a folder takes effect within the next
 * 24h window without any explicit re-enable.
 *
 * Returns [Result.retry] only on genuine throw (network blip, provider rate-limit).
 * The state-mapping inside the controller already routes "URI revoked" to a UI error
 * state, so we don't double-handle it here.
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params), KoinComponent {
    private val controller: BackupController by inject()
    private val store: BackupFolderStore by inject()
    private val settings: SettingsRepository by inject()

    override suspend fun doWork(): Result =
        runCatching {
            if (store.treeUriNow().isNullOrEmpty()) {
                Log.d(LOG_TAG, "skipping: no backup folder configured")
                return@runCatching Result.success()
            }
            val dailyEnabled =
                settings.getMetaNow(SettingsRepository.KEY_DAILY_CHECK)?.toBoolean() ?: true
            if (!dailyEnabled) {
                Log.d(LOG_TAG, "skipping: daily check is disabled in Scheduler details")
                return@runCatching Result.success()
            }
            controller.runBackupAwaiting()
            Result.success()
        }.getOrElse { t ->
            Log.w(LOG_TAG, "backup worker threw: ${t.message}")
            Result.retry()
        }

    private companion object {
        const val LOG_TAG = "Kofipod-Backup"
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kofikodr.kofipod.backup.BackupController
import com.kofikodr.kofipod.backup.BackupFolderStore
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Periodic SAF-backup worker. Mirrors the shape of
 * [com.kofikodr.kofipod.background.EpisodeCheckWorker]: a thin shell over the same
 * [BackupController] entry point the manual button calls. Single-flight is enforced
 * by the controller, so a manual run + worker tick concurrent fire still produces
 * exactly one write.
 *
 * No-op when no folder URI is set — that's the user's "off switch." We don't fail or
 * retry in that case; the scheduler stays enabled so the moment a folder is picked,
 * the next worker tick has work to do.
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

    override suspend fun doWork(): Result =
        runCatching {
            if (store.treeUriNow().isNullOrEmpty()) {
                Log.d(LOG_TAG, "skipping: no backup folder configured")
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

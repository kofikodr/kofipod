// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

actual class BackupScheduler(private val context: Context) {
    /**
     * Schedules the daily SAF backup. Charging + unmetered network = the user is
     * docked at home on Wi-Fi and won't notice a few hundred KB of cloud upload. The
     * 24h interval is WorkManager's flex window — actual fire time may be ±some hours
     * depending on Doze. The worker no-ops without a folder URI so this is safe to
     * leave always-enabled even before the user picks a folder.
     */
    actual fun enable() {
        val req =
            PeriodicWorkRequestBuilder<BackupWorker>(BACKUP_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(
                    Constraints
                        .Builder()
                        .setRequiresCharging(true)
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build(),
                )
                .addTag(TAG)
                .build()
        WorkManager
            .getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    actual fun disable() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    companion object {
        const val UNIQUE_NAME = "saf_backup"
        const val TAG = "saf_backup"
        private const val BACKUP_INTERVAL_HOURS = 24L
    }
}

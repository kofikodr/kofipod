// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import android.app.Application
import app.kofipod.ai.AiSummaryRepository
import app.kofipod.background.BackupScheduler
import app.kofipod.backup.PendingRestore
import app.kofipod.di.androidPlatformModule
import app.kofipod.di.commonDataModule
import app.kofipod.ui.theme.ThemeSystem
import app.kofipod.update.UpdateInstaller
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.java.KoinJavaComponent.get

class KofipodApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // MUST run before startKoin: if the user just confirmed a restore, the
        // SQLDelight driver hasn't opened `kofipod.db` yet. Once Koin builds the DB
        // singleton, the file is locked and we'd have to teardown to overwrite it.
        // PendingRestore handles "no pending" as a fast no-op.
        PendingRestore.consumeIfPresent(this)
        ThemeSystem.applyPersistedToProcess(this)
        startKoin {
            androidLogger()
            androidContext(this@KofipodApplication)
            modules(commonDataModule, androidPlatformModule)
        }
        // The downloaded-APK pointer rides Auto Backup but the file itself doesn't —
        // clear it on cold start if the file isn't where the pointer says it is, so a
        // restored device doesn't crash on "Install".
        get<UpdateInstaller>(UpdateInstaller::class.java).reconcileDownloadedApk()
        // Recover any AI summary requests that were mid-flight when the previous
        // process died. Worker is the primary backstop while the app is killed,
        // but we also kick on every cold start so a worker that was throttled
        // (or never reached the OS scheduler) doesn't leave the marker stuck.
        get<AiSummaryRepository>(AiSummaryRepository::class.java).resumePendingAsync()
        // Always-on: BackupWorker is a no-op when no folder URI is set, so enabling
        // unconditionally on cold start costs nothing and means the moment a user
        // picks a folder, the next 24h tick has work to do.
        get<BackupScheduler>(BackupScheduler::class.java).enable()
    }
}

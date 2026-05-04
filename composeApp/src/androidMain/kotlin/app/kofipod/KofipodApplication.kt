// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import android.app.Application
import app.kofipod.ai.AiSummaryRepository
import app.kofipod.background.BackupScheduler
import app.kofipod.backup.PendingRestore
import app.kofipod.di.androidPlatformModule
import app.kofipod.di.commonDataModule
import app.kofipod.diagnostics.DiagnosticsBootstrapper
import app.kofipod.diagnostics.Telemetry
import app.kofipod.diagnostics.TelemetryEvent
import app.kofipod.ui.theme.ThemeSystem
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
        // Recover any AI summary requests that were mid-flight when the previous
        // process died. Worker is the primary backstop while the app is killed,
        // but we also kick on every cold start so a worker that was throttled
        // (or never reached the OS scheduler) doesn't leave the marker stuck.
        get<AiSummaryRepository>(AiSummaryRepository::class.java).resumePendingAsync()
        // Always-on: BackupWorker is a no-op when no folder URI is set, so enabling
        // unconditionally on cold start costs nothing and means the moment a user
        // picks a folder, the next 24h tick has work to do.
        get<BackupScheduler>(BackupScheduler::class.java).enable()
        // Wire diagnostics flag flows to SDK enable/disable. Until the user
        // acknowledges the first-launch disclosure, both subsystems stay
        // disabled regardless of toggle state.
        get<DiagnosticsBootstrapper>(DiagnosticsBootstrapper::class.java).start()
        // Telemetry no-op when disabled or pre-acknowledgement, so this is safe.
        get<Telemetry>(Telemetry::class.java).track(TelemetryEvent.AppOpened)
    }
}

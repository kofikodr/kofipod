// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod

import android.app.Application
import app.kofipod.ai.AiSummaryRepository
import app.kofipod.diagnostics.DiagnosticsBootstrapper
import app.kofipod.diagnostics.Telemetry
import app.kofipod.diagnostics.TelemetryEvent
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
        // Wire diagnostics flag flows to SDK enable/disable. Until the user
        // acknowledges the first-launch disclosure, both subsystems stay
        // disabled regardless of toggle state.
        get<DiagnosticsBootstrapper>(DiagnosticsBootstrapper::class.java).start()
        // Telemetry no-op when disabled or pre-acknowledgement, so this is safe.
        get<Telemetry>(Telemetry::class.java).track(TelemetryEvent.AppOpened)
    }
}

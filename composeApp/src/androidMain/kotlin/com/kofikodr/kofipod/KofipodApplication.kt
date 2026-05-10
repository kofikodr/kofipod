// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod

import android.app.Application
import com.kofikodr.kofipod.ai.AiSummaryRepository
import com.kofikodr.kofipod.background.BackupScheduler
import com.kofikodr.kofipod.backup.PendingRestore
import com.kofikodr.kofipod.di.androidPlatformModule
import com.kofikodr.kofipod.di.commonDataModule
import com.kofikodr.kofipod.di.flavorPlatformModule
import com.kofikodr.kofipod.diagnostics.DiagnosticsBootstrapper
import com.kofikodr.kofipod.diagnostics.Telemetry
import com.kofikodr.kofipod.diagnostics.TelemetryEvent
import com.kofikodr.kofipod.diagnostics.toTelemetryTag
import com.kofikodr.kofipod.pro.ProEntitlementRepository
import com.kofikodr.kofipod.ui.theme.ThemeSystem
import com.kofikodr.kofipod.update.UpdateInstaller
import com.kofikodr.kofipod.update.UpdaterCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.android.ext.koin.androidContext
import org.koin.android.ext.koin.androidLogger
import org.koin.core.context.startKoin
import org.koin.core.qualifier.named
import org.koin.java.KoinJavaComponent.get
import org.koin.mp.KoinPlatform

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
            modules(commonDataModule, androidPlatformModule, flavorPlatformModule)
        }
        // The downloaded-APK pointer rides Auto Backup but the file itself doesn't —
        // clear it on cold start if the file isn't where the pointer says it is, so a
        // restored device doesn't crash on "Install". Capability-gated so the Play
        // Store flavor can ship without the sideload updater.
        if (UpdaterCapability.enabled) {
            get<UpdateInstaller>(UpdateInstaller::class.java).reconcileDownloadedApk()
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
        val bootstrapper = get<DiagnosticsBootstrapper>(DiagnosticsBootstrapper::class.java)
        bootstrapper.start()
        val appScope = KoinPlatform.getKoin().get<CoroutineScope>(named("appScope"))
        // AppOpened must wait until the bootstrapper has actually called
        // Telemetry.enable() — firing track() too early loses the event because
        // the SDK isn't initialized yet. The telemetryReady flow flips only
        // AFTER enable() returns, eliminating the cold-start race.
        val telemetry = get<Telemetry>(Telemetry::class.java)
        // Kick Pro entitlement reconciliation eagerly. Repository hydrates from
        // cache first, then refreshes from Play Billing — so paywall-gated UI
        // sees the right tier within a few hundred ms of process start. Failure
        // here is non-fatal (UI shows Unknown until the user retries via Settings).
        val pro = get<ProEntitlementRepository>(ProEntitlementRepository::class.java)
        appScope.launch {
            pro.refreshOnStart()
        }
        appScope.launch {
            // Bound the await: if the bootstrapper never flips telemetryReady
            // true (disclosure not acknowledged, telemetry toggle off, or SDK
            // init failed), this coroutine would otherwise suspend for the
            // process lifetime. 5s is generous — readiness usually settles in
            // <50ms once prefs are decrypted.
            val ready = withTimeoutOrNull(5_000) { bootstrapper.telemetryReady.first { it } }
            if (ready != true) return@launch
            // Also wait for the entitlement cache to hydrate so AppOpened's
            // pro_source prop reflects the cached tier instead of Unknown.
            // Bounded by a tight timeout because hydrateFromCache is just a
            // backup-excluded prefs read — should settle in <50ms. If it
            // doesn't, fire AppOpened anyway with Unknown rather than dropping
            // the event.
            withTimeoutOrNull(1_000) { pro.hydrateFromCache() }
            telemetry.track(TelemetryEvent.AppOpened(pro.state.value.toTelemetryTag()))
        }
    }
}

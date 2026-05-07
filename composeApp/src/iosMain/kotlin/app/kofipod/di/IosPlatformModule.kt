// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.ai.IosKeyVaultStub
import app.kofipod.ai.KeyVault
import app.kofipod.background.AiSummaryScheduler
import app.kofipod.background.BackupScheduler
import app.kofipod.background.IosAiSummaryScheduler
import app.kofipod.background.IosPkmExportScheduler
import app.kofipod.background.PkmExportScheduler
import app.kofipod.backup.BackupFilePort
import app.kofipod.backup.BackupFolderStore
import app.kofipod.backup.DbFileBytes
import app.kofipod.backup.IosBackupFilePort
import app.kofipod.backup.IosBackupFolderStore
import app.kofipod.backup.StageDbFile
import app.kofipod.diagnostics.CrashReporter
import app.kofipod.diagnostics.DiagnosticsConfigRepository
import app.kofipod.diagnostics.NoOpCrashReporter
import app.kofipod.diagnostics.NoOpDiagnosticsConfigRepository
import app.kofipod.diagnostics.NoOpTelemetry
import app.kofipod.diagnostics.Telemetry
import app.kofipod.network.IosNetworkMonitor
import app.kofipod.network.NetworkMonitor
import app.kofipod.opml.IosOpmlFilePort
import app.kofipod.opml.OpmlFilePort
import app.kofipod.pro.BillingClientPort
import app.kofipod.pro.EntitlementCache
import app.kofipod.pro.IosBillingClientPort
import app.kofipod.pro.IosEntitlementCache
import app.kofipod.ui.palette.IosPalettePort
import app.kofipod.ui.palette.PalettePort
import app.kofipod.ui.screens.settings.IosUpdateActionPort
import app.kofipod.ui.screens.settings.UpdateActionPort
import app.kofipod.update.IosLocalApkPathStore
import app.kofipod.update.LocalApkPathStore
import app.kofipod.update.UpdateChecker
import org.koin.dsl.module

/**
 * iOS platform Koin bindings. iOS is secondary today — there is no `startKoin` entry
 * point on iOS, so this module is unused at runtime. It exists so the iOS Koin graph
 * matches Android's when the iOS target is wired up for real.
 */
val iosPlatformModule =
    module {
        single<NetworkMonitor> { IosNetworkMonitor() }
        single<UpdateActionPort> { IosUpdateActionPort() }
        single<PalettePort> { IosPalettePort() }
        single<LocalApkPathStore> { IosLocalApkPathStore() }
        single { UpdateChecker() }
        single<KeyVault> { IosKeyVaultStub() }
        single<BillingClientPort> { IosBillingClientPort() }
        single<EntitlementCache> { IosEntitlementCache() }
        single<AiSummaryScheduler> { IosAiSummaryScheduler() }
        single<PkmExportScheduler> { IosPkmExportScheduler() }
        // PKM (Slice 6) — iOS stubs to keep the Koin graph parity with Android.
        // Obsidian / OAuth vault are non-functional on iOS in v1.0; the actuals
        // throw / use an in-memory map respectively.
        single<app.kofipod.pkm.connections.OAuthTokenVault> {
            app.kofipod.pkm.connections.OAuthTokenVaultImpl()
        }
        single { app.kofipod.pkm.sinks.ObsidianFolderWriterImpl() }
        single { BackupScheduler() }
        single<OpmlFilePort> { IosOpmlFilePort() }
        single<BackupFilePort> { IosBackupFilePort() }
        single<BackupFolderStore> { IosBackupFolderStore() }
        single<DbFileBytes> { DbFileBytes { error("backup not supported on iOS") } }
        single<StageDbFile> { StageDbFile { error("backup not supported on iOS") } }
        single<CrashReporter> { NoOpCrashReporter }
        single<Telemetry> { NoOpTelemetry }
        single<DiagnosticsConfigRepository> { NoOpDiagnosticsConfigRepository }
    }

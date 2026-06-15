// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.di

import com.kofikodr.kofipod.ai.IosKeyVaultStub
import com.kofikodr.kofipod.ai.KeyVault
import com.kofikodr.kofipod.background.AiSummaryScheduler
import com.kofikodr.kofipod.background.BackupScheduler
import com.kofikodr.kofipod.background.IosAiSummaryScheduler
import com.kofikodr.kofipod.background.IosPkmExportScheduler
import com.kofikodr.kofipod.background.PkmExportScheduler
import com.kofikodr.kofipod.backup.BackupFilePort
import com.kofikodr.kofipod.backup.BackupFolderStore
import com.kofikodr.kofipod.backup.DbFileBytes
import com.kofikodr.kofipod.backup.IosBackupFilePort
import com.kofikodr.kofipod.backup.IosBackupFolderStore
import com.kofikodr.kofipod.backup.StageDbFile
import com.kofikodr.kofipod.data.api.IosPodcastIndexCredentialStoreStub
import com.kofikodr.kofipod.data.api.PodcastIndexCredentialStore
import com.kofikodr.kofipod.data.search.IosItunesStorefrontStore
import com.kofikodr.kofipod.data.search.ItunesStorefrontStore
import com.kofikodr.kofipod.diagnostics.CrashReporter
import com.kofikodr.kofipod.diagnostics.DiagnosticsConfigRepository
import com.kofikodr.kofipod.diagnostics.NoOpCrashReporter
import com.kofikodr.kofipod.diagnostics.NoOpDiagnosticsConfigRepository
import com.kofikodr.kofipod.diagnostics.NoOpTelemetry
import com.kofikodr.kofipod.diagnostics.Telemetry
import com.kofikodr.kofipod.network.IosNetworkMonitor
import com.kofikodr.kofipod.network.NetworkMonitor
import com.kofikodr.kofipod.opml.IosOpmlFilePort
import com.kofikodr.kofipod.opml.OpmlFilePort
import com.kofikodr.kofipod.pro.BillingClientPort
import com.kofikodr.kofipod.pro.EntitlementCache
import com.kofikodr.kofipod.pro.IosBillingClientPort
import com.kofikodr.kofipod.pro.IosEntitlementCache
import com.kofikodr.kofipod.ui.palette.IosPalettePort
import com.kofikodr.kofipod.ui.palette.PalettePort
import com.kofikodr.kofipod.ui.screens.settings.IosUpdateActionPort
import com.kofikodr.kofipod.ui.screens.settings.UpdateActionPort
import com.kofikodr.kofipod.update.IosLocalApkPathStore
import com.kofikodr.kofipod.update.LocalApkPathStore
import com.kofikodr.kofipod.update.UpdateChecker
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
        single<PodcastIndexCredentialStore> { IosPodcastIndexCredentialStoreStub() }
        single<BillingClientPort> { IosBillingClientPort() }
        single<EntitlementCache> { IosEntitlementCache() }
        single<AiSummaryScheduler> { IosAiSummaryScheduler() }
        single<PkmExportScheduler> { IosPkmExportScheduler() }
        // PKM (Slice 6) — iOS stubs to keep the Koin graph parity with Android.
        // Obsidian / OAuth vault are non-functional on iOS in v1.0; the actuals
        // throw / use an in-memory map respectively.
        single<com.kofikodr.kofipod.pkm.connections.OAuthTokenVault> {
            com.kofikodr.kofipod.pkm.connections.OAuthTokenVaultImpl()
        }
        single { com.kofikodr.kofipod.pkm.sinks.ObsidianFolderWriterImpl() }
        single { BackupScheduler() }
        single<OpmlFilePort> { IosOpmlFilePort() }
        single<BackupFilePort> { IosBackupFilePort() }
        single<BackupFolderStore> { IosBackupFolderStore() }
        single<ItunesStorefrontStore> { IosItunesStorefrontStore() }
        single<DbFileBytes> { DbFileBytes { error("backup not supported on iOS") } }
        single<StageDbFile> { StageDbFile { error("backup not supported on iOS") } }
        single<CrashReporter> { NoOpCrashReporter }
        single<Telemetry> { NoOpTelemetry }
        single<DiagnosticsConfigRepository> { NoOpDiagnosticsConfigRepository }
    }

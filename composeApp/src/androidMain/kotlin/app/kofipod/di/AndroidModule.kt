// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.ai.AndroidKeyVault
import app.kofipod.ai.KeyVault
import app.kofipod.background.AiSummaryScheduler
import app.kofipod.background.AndroidAiSummaryScheduler
import app.kofipod.background.BackupScheduler
import app.kofipod.background.Notifier
import app.kofipod.background.Scheduler
import app.kofipod.backup.AndroidBackupFilePort
import app.kofipod.backup.AndroidBackupFolderStore
import app.kofipod.backup.BackupController
import app.kofipod.backup.BackupFilePort
import app.kofipod.backup.BackupFolderStore
import app.kofipod.backup.DbFileBytes
import app.kofipod.backup.StageDbFile
import app.kofipod.data.db.DatabaseFactory
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.diagnostics.AndroidCrashReporter
import app.kofipod.diagnostics.AndroidDiagnosticsConfigRepository
import app.kofipod.diagnostics.AndroidTelemetry
import app.kofipod.diagnostics.CrashReporter
import app.kofipod.diagnostics.DiagnosticsConfigRepository
import app.kofipod.diagnostics.Telemetry
import app.kofipod.downloads.DownloadEngine
import app.kofipod.downloads.DownloadEngineApi
import app.kofipod.network.AndroidNetworkMonitor
import app.kofipod.network.NetworkMonitor
import app.kofipod.opml.AndroidOpmlFilePort
import app.kofipod.opml.OpmlFilePort
import app.kofipod.playback.KofipodPlayer
import app.kofipod.playback.PlaybackCache
import app.kofipod.pro.AndroidEntitlementCache
import app.kofipod.pro.EntitlementCache
import app.kofipod.share.Sharer
import app.kofipod.ui.ActivityHolder
import app.kofipod.ui.palette.AndroidPalettePort
import app.kofipod.ui.palette.PalettePort
import app.kofipod.ui.theme.ThemeSystem
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module
import java.io.File

val androidPlatformModule =
    module {
        single { DatabaseFactory(androidContext()) }
        single { KofipodPlayer(androidContext()) }
        single { DownloadEngine(androidContext()) }
        single<DownloadEngineApi> { get<DownloadEngine>() }
        single<NetworkMonitor> { AndroidNetworkMonitor(androidContext()) }
        single {
            // Read the cap synchronously at Koin resolution; SimpleCache is constructed once per
            // process and can't be re-sized without reopening, so later slider changes apply on
            // next process start.
            val capBytes = get<SettingsRepository>().streamCacheCapBytesNow()
            PlaybackCache(androidContext(), capBytes)
        }
        single { Scheduler(androidContext()) }
        single { BackupScheduler(androidContext()) }
        single<AiSummaryScheduler> { AndroidAiSummaryScheduler(androidContext()) }
        single { Notifier(androidContext()) }
        single { Sharer(androidContext()) }
        single { ThemeSystem(androidContext()) }
        single<PalettePort> { AndroidPalettePort(androidContext()) }
        single<KeyVault> { AndroidKeyVault(androidContext()) }
        single<EntitlementCache> { AndroidEntitlementCache(androidContext()) }
        single { ActivityHolder() }
        single<DiagnosticsConfigRepository> { AndroidDiagnosticsConfigRepository(androidContext()) }
        single<CrashReporter> { AndroidCrashReporter() }
        single<Telemetry> { AndroidTelemetry(androidContext()) }
        // The Android OPML port is a singleton that the picker-host composable subscribes
        // to. Both the interface and the concrete type resolve to the same instance so the
        // composable (which casts to the concrete) sees what the VM (which uses the
        // interface) is signalling.
        single { AndroidOpmlFilePort() }
        single<OpmlFilePort> { get<AndroidOpmlFilePort>() }
        // SAF backup wiring. Both the interface binding and the concrete
        // AndroidBackupFilePort resolve to the same instance so the Compose
        // picker host (which reads the concrete) sees what the controller
        // (which uses the interface) signals.
        single { AndroidBackupFilePort(androidContext()) }
        single<BackupFilePort> { get<AndroidBackupFilePort>() }
        single<BackupFolderStore> { AndroidBackupFolderStore(androidContext()) }
        single<DbFileBytes> {
            val ctx = androidContext()
            val driver = get<app.cash.sqldelight.db.SqlDriver>()
            DbFileBytes {
                // Force a WAL checkpoint so the on-disk `kofipod.db` file contains
                // every committed transaction. Without this step, recent writes
                // sitting in the `-wal` sidecar would be missing from the snapshot
                // and a restored copy would silently roll the user back to the last
                // checkpoint. TRUNCATE leaves the WAL file empty afterwards.
                driver.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { app.cash.sqldelight.db.QueryResult.Unit }, 0)
                ctx.getDatabasePath("kofipod.db").readBytes()
            }
        }
        single<StageDbFile> {
            val ctx = androidContext()
            StageDbFile { bytes ->
                File(ctx.filesDir, BackupController.STAGED_FILENAME).writeBytes(bytes)
            }
        }
    }

// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.di

import com.kofikodr.kofipod.ai.AndroidKeyVault
import com.kofikodr.kofipod.ai.KeyVault
import com.kofikodr.kofipod.background.AiSummaryScheduler
import com.kofikodr.kofipod.background.AndroidAiSummaryScheduler
import com.kofikodr.kofipod.background.AndroidPkmExportScheduler
import com.kofikodr.kofipod.background.BackupScheduler
import com.kofikodr.kofipod.background.Notifier
import com.kofikodr.kofipod.background.PkmExportScheduler
import com.kofikodr.kofipod.background.Scheduler
import com.kofikodr.kofipod.backup.AndroidBackupFilePort
import com.kofikodr.kofipod.backup.AndroidBackupFolderStore
import com.kofikodr.kofipod.backup.BackupController
import com.kofikodr.kofipod.backup.BackupFilePort
import com.kofikodr.kofipod.backup.BackupFolderStore
import com.kofikodr.kofipod.backup.DbFileBytes
import com.kofikodr.kofipod.backup.StageDbFile
import com.kofikodr.kofipod.data.db.DatabaseFactory
import com.kofikodr.kofipod.data.repo.SettingsRepository
import com.kofikodr.kofipod.data.search.AndroidItunesStorefrontStore
import com.kofikodr.kofipod.data.search.ItunesStorefrontStore
import com.kofikodr.kofipod.diagnostics.AndroidCrashReporter
import com.kofikodr.kofipod.diagnostics.AndroidDiagnosticsConfigRepository
import com.kofikodr.kofipod.diagnostics.AndroidTelemetry
import com.kofikodr.kofipod.diagnostics.CrashReporter
import com.kofikodr.kofipod.diagnostics.DiagnosticsConfigRepository
import com.kofikodr.kofipod.diagnostics.Telemetry
import com.kofikodr.kofipod.downloads.DownloadEngine
import com.kofikodr.kofipod.downloads.DownloadEngineApi
import com.kofikodr.kofipod.network.AndroidNetworkMonitor
import com.kofikodr.kofipod.network.NetworkMonitor
import com.kofikodr.kofipod.opml.AndroidOpmlFilePort
import com.kofikodr.kofipod.opml.OpmlFilePort
import com.kofikodr.kofipod.playback.KofipodPlayer
import com.kofikodr.kofipod.playback.PlaybackCache
import com.kofikodr.kofipod.pro.AndroidEntitlementCache
import com.kofikodr.kofipod.pro.EntitlementCache
import com.kofikodr.kofipod.share.Sharer
import com.kofikodr.kofipod.snippets.FileChecker
import com.kofikodr.kofipod.snippets.PcmDecoder
import com.kofikodr.kofipod.snippets.SnippetExporter
import com.kofikodr.kofipod.snippets.SnippetRenderLauncher
import com.kofikodr.kofipod.ui.ActivityHolder
import com.kofikodr.kofipod.ui.palette.AndroidPalettePort
import com.kofikodr.kofipod.ui.palette.PalettePort
import com.kofikodr.kofipod.ui.screens.settings.AndroidUpdateActionPort
import com.kofikodr.kofipod.ui.screens.settings.UpdateActionPort
import com.kofikodr.kofipod.ui.theme.ThemeSystem
import com.kofikodr.kofipod.update.AndroidLocalApkPathStore
import com.kofikodr.kofipod.update.LocalApkPathStore
import com.kofikodr.kofipod.update.UpdateChecker
import com.kofikodr.kofipod.update.UpdateInstaller
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
        single<PkmExportScheduler> { AndroidPkmExportScheduler(androidContext()) }
        single { Notifier(androidContext()) }
        single { Sharer(androidContext()) }
        // Snippets (Slice 3) — Context-bound bindings live alongside Sharer because
        // they need an Android Context for FileProvider, MediaCodec, and the
        // foreground service launcher.
        single { FileChecker() }
        // Resolver injects the FileCheckerApi interface (commonMain), so Koin
        // needs an explicit binding mapping the interface to the actual.
        // Without this, SnippetRenderService crashes at first render with
        // NoDefinitionFoundException for FileCheckerApi.
        single<com.kofikodr.kofipod.snippets.FileCheckerApi> { get<FileChecker>() }
        // PcmDecoder backs the snippet MP4 audio-reactive bars overlay (per-frame
        // RMS comes from the source audio, not synthetic wiggle). Bound here as
        // the Android-only actual; iOS gets a NotImplementedError stub.
        single { PcmDecoder(androidContext()) }
        single { SnippetExporter(androidContext(), get()) }
        single { SnippetRenderLauncher(androidContext()) }
        // PKM (Slice 5 + 6) — platform ports and connection vault.
        // ClipboardPort and MarkdownTempFilePort are concrete-only `actual class`es
        // (no expect class interface), so a single binding each is sufficient.
        // OAuthTokenVaultImpl is the Android actual (EncryptedSharedPreferences); iOS
        // actual is a no-op in-memory stub. Bound as the interface so commonMain
        // callers (PkmConnectionRepository) resolve it without platform knowledge.
        single { com.kofikodr.kofipod.pkm.ClipboardPort(androidContext()) }
        single { com.kofikodr.kofipod.pkm.MarkdownTempFilePort(androidContext()) }
        single<com.kofikodr.kofipod.pkm.connections.OAuthTokenVault> {
            com.kofikodr.kofipod.pkm.connections.OAuthTokenVaultImpl(androidContext())
        }
        single { com.kofikodr.kofipod.pkm.sinks.ObsidianFolderWriterImpl(androidContext()) }
        single { ThemeSystem(androidContext()) }
        single<PalettePort> { AndroidPalettePort(androidContext()) }
        single<LocalApkPathStore> { AndroidLocalApkPathStore(androidContext()) }
        single { UpdateChecker(api = get(), repo = get()) }
        single { UpdateInstaller(context = androidContext(), httpClient = get(), repo = get()) }
        single<UpdateActionPort> { AndroidUpdateActionPort(installer = get()) }
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
        single<ItunesStorefrontStore> { AndroidItunesStorefrontStore(androidContext()) }
        single<DbFileBytes> {
            val ctx = androidContext()
            val driver = get<app.cash.sqldelight.db.SqlDriver>()
            DbFileBytes {
                // Force a WAL checkpoint so the on-disk `kofipod.db` file contains
                // every committed transaction. Without this step, recent writes
                // sitting in the `-wal` sidecar would be missing from the snapshot
                // and a restored copy would silently roll the user back to the last
                // checkpoint. TRUNCATE leaves the WAL file empty afterwards.
                //
                // PRAGMA wal_checkpoint returns one row of (busy, log, checkpointed):
                //   busy = 1            → another connection was writing; we MUST NOT
                //                         emit a backup, the WAL still holds uncommitted
                //                         transactions we'd silently drop.
                //   log > checkpointed  → some frames couldn't be replayed; same risk.
                // Retry a few times with a short backoff to ride out concurrent writes,
                // then surface as an error so the user sees a failed backup instead of
                // a silently-incomplete one.
                checkpointOrThrow(driver)
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

/**
 * Runs `PRAGMA wal_checkpoint(TRUNCATE)` and asserts the WAL is fully drained
 * before returning. The PRAGMA returns one row: `(busy, log, checkpointed)`.
 * `busy != 0` means another connection had a write lock and we could not
 * grab it; `log > checkpointed` means some frames remained un-replayed. Either
 * case means the on-disk DB file is missing committed transactions.
 *
 * Retries [MAX_CHECKPOINT_ATTEMPTS] times with a short backoff to ride out
 * concurrent writes (the app's own collectors are the most likely source).
 * After the cap, throws so the caller (BackupController) surfaces a failed
 * backup rather than silently emitting an incomplete one.
 *
 * Internal so the unit test in androidUnitTest can call it directly.
 */
internal suspend fun checkpointOrThrow(driver: app.cash.sqldelight.db.SqlDriver) {
    var lastResult: CheckpointResult? = null
    for (attempt in 1..MAX_CHECKPOINT_ATTEMPTS) {
        val result = runCheckpoint(driver)
        if (result.busy == 0 && result.log <= result.checkpointed) return
        lastResult = result
        if (attempt < MAX_CHECKPOINT_ATTEMPTS) {
            kotlinx.coroutines.delay(CHECKPOINT_RETRY_BACKOFF_MS)
        }
    }
    error(
        "WAL checkpoint did not drain after $MAX_CHECKPOINT_ATTEMPTS attempts " +
            "(busy=${lastResult?.busy}, log=${lastResult?.log}, " +
            "checkpointed=${lastResult?.checkpointed}). Backup would be incomplete.",
    )
}

internal data class CheckpointResult(
    val busy: Int,
    val log: Int,
    val checkpointed: Int,
)

private const val MAX_CHECKPOINT_ATTEMPTS = 5
private const val CHECKPOINT_RETRY_BACKOFF_MS = 50L

private fun runCheckpoint(driver: app.cash.sqldelight.db.SqlDriver): CheckpointResult =
    driver.executeQuery(
        identifier = null,
        sql = "PRAGMA wal_checkpoint(TRUNCATE)",
        mapper = { cursor ->
            val parsed =
                if (cursor.next().value) {
                    CheckpointResult(
                        busy = cursor.getLong(0)?.toInt() ?: 1,
                        log = cursor.getLong(1)?.toInt() ?: Int.MAX_VALUE,
                        checkpointed = cursor.getLong(2)?.toInt() ?: 0,
                    )
                } else {
                    // No row returned — treat as failed checkpoint. The PRAGMA
                    // always emits a row in practice, but fail-closed if not.
                    CheckpointResult(busy = 1, log = Int.MAX_VALUE, checkpointed = 0)
                }
            app.cash.sqldelight.db.QueryResult.Value(parsed)
        },
        parameters = 0,
    ).value

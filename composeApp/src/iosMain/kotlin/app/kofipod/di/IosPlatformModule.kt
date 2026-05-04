// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.ai.IosKeyVaultStub
import app.kofipod.ai.KeyVault
import app.kofipod.background.AiSummaryScheduler
import app.kofipod.background.BackupScheduler
import app.kofipod.background.IosAiSummaryScheduler
import app.kofipod.backup.BackupFilePort
import app.kofipod.backup.BackupFolderStore
import app.kofipod.backup.DbFileBytes
import app.kofipod.backup.IosBackupFilePort
import app.kofipod.backup.IosBackupFolderStore
import app.kofipod.backup.StageDbFile
import app.kofipod.network.IosNetworkMonitor
import app.kofipod.network.NetworkMonitor
import app.kofipod.opml.IosOpmlFilePort
import app.kofipod.opml.OpmlFilePort
import app.kofipod.ui.palette.IosPalettePort
import app.kofipod.ui.palette.PalettePort
import org.koin.dsl.module

/**
 * iOS platform Koin bindings. iOS is secondary today — there is no `startKoin` entry
 * point on iOS, so this module is unused at runtime. It exists so the iOS Koin graph
 * matches Android's when the iOS target is wired up for real.
 */
val iosPlatformModule =
    module {
        single<NetworkMonitor> { IosNetworkMonitor() }
        single<PalettePort> { IosPalettePort() }
        single<KeyVault> { IosKeyVaultStub() }
        single<AiSummaryScheduler> { IosAiSummaryScheduler() }
        single { BackupScheduler() }
        single<OpmlFilePort> { IosOpmlFilePort() }
        single<BackupFilePort> { IosBackupFilePort() }
        single<BackupFolderStore> { IosBackupFolderStore() }
        single<DbFileBytes> { DbFileBytes { error("backup not supported on iOS") } }
        single<StageDbFile> { StageDbFile { error("backup not supported on iOS") } }
    }

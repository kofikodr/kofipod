// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.ai.AndroidKeyVault
import app.kofipod.ai.KeyVault
import app.kofipod.background.AiSummaryScheduler
import app.kofipod.background.AndroidAiSummaryScheduler
import app.kofipod.background.Notifier
import app.kofipod.background.Scheduler
import app.kofipod.data.db.DatabaseFactory
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.downloads.DownloadEngine
import app.kofipod.downloads.DownloadEngineApi
import app.kofipod.network.AndroidNetworkMonitor
import app.kofipod.network.NetworkMonitor
import app.kofipod.opml.AndroidOpmlFilePort
import app.kofipod.opml.OpmlFilePort
import app.kofipod.playback.KofipodPlayer
import app.kofipod.playback.PlaybackCache
import app.kofipod.share.Sharer
import app.kofipod.ui.palette.AndroidPalettePort
import app.kofipod.ui.palette.PalettePort
import app.kofipod.ui.screens.settings.AndroidUpdateActionPort
import app.kofipod.ui.screens.settings.UpdateActionPort
import app.kofipod.ui.theme.ThemeSystem
import app.kofipod.update.AndroidLocalApkPathStore
import app.kofipod.update.LocalApkPathStore
import app.kofipod.update.UpdateChecker
import app.kofipod.update.UpdateInstaller
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

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
        single<AiSummaryScheduler> { AndroidAiSummaryScheduler(androidContext()) }
        single { Notifier(androidContext()) }
        single { Sharer(androidContext()) }
        single { ThemeSystem(androidContext()) }
        single<PalettePort> { AndroidPalettePort(androidContext()) }
        single<LocalApkPathStore> { AndroidLocalApkPathStore(androidContext()) }
        single { UpdateChecker(api = get(), repo = get()) }
        single { UpdateInstaller(context = androidContext(), httpClient = get(), repo = get()) }
        single<UpdateActionPort> { AndroidUpdateActionPort(installer = get()) }
        single<KeyVault> { AndroidKeyVault(androidContext()) }
        // The Android OPML port is a singleton that the picker-host composable subscribes
        // to. Both the interface and the concrete type resolve to the same instance so the
        // composable (which casts to the concrete) sees what the VM (which uses the
        // interface) is signalling.
        single { AndroidOpmlFilePort() }
        single<OpmlFilePort> { get<AndroidOpmlFilePort>() }
    }

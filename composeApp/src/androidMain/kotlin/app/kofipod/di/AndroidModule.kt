// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.background.Notifier
import app.kofipod.background.Scheduler
import app.kofipod.data.db.DatabaseFactory
import app.kofipod.data.repo.SettingsRepository
import app.kofipod.downloads.DownloadEngine
import app.kofipod.downloads.DownloadEngineApi
import app.kofipod.network.NetworkMonitor
import app.kofipod.playback.KofipodPlayer
import app.kofipod.playback.PlaybackCache
import app.kofipod.share.Sharer
import app.kofipod.ui.theme.ThemeSystem
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidPlatformModule =
    module {
        single { DatabaseFactory(androidContext()) }
        single { KofipodPlayer(androidContext()) }
        single { DownloadEngine(androidContext()) }
        single<DownloadEngineApi> { get<DownloadEngine>() }
        single { NetworkMonitor(androidContext()) }
        single {
            // Read the cap synchronously at Koin resolution; SimpleCache is constructed once per
            // process and can't be re-sized without reopening, so later slider changes apply on
            // next process start.
            val capBytes = get<SettingsRepository>().streamCacheCapBytesNow()
            PlaybackCache(androidContext(), capBytes)
        }
        single { Scheduler(androidContext()) }
        single { Notifier(androidContext()) }
        single { Sharer(androidContext()) }
        single { ThemeSystem(androidContext()) }
    }

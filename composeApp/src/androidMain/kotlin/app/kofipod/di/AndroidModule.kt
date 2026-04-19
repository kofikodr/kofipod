// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.background.Notifier
import app.kofipod.background.Scheduler
import app.kofipod.data.db.DatabaseFactory
import app.kofipod.downloads.DownloadEngine
import app.kofipod.playback.KofipodPlayer
import app.kofipod.share.Sharer
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val androidPlatformModule =
    module {
        single { DatabaseFactory(androidContext()) }
        single { KofipodPlayer(androidContext()) }
        single { DownloadEngine(androidContext()) }
        single { Scheduler(androidContext()) }
        single { Notifier(androidContext()) }
        single { Sharer(androidContext()) }
    }

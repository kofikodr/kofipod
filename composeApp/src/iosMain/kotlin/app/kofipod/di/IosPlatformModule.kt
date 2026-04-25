// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.network.IosNetworkMonitor
import app.kofipod.network.NetworkMonitor
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
    }

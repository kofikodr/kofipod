// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.network.IosNetworkMonitor
import app.kofipod.network.NetworkMonitor
import org.koin.dsl.module

/**
 * iOS platform Koin bindings. iOS is secondary today — there is no `startKoin` entry
 * point on iOS, so this module is unused at runtime. It exists so the iOS Koin graph
 * matches Android's when the iOS target is wired up for real.
 */
val iosPlatformModule =
    module {
        single<NetworkMonitor> { IosNetworkMonitor() }
    }

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.ai.IosKeyVaultStub
import app.kofipod.ai.KeyVault
import app.kofipod.background.AiSummaryScheduler
import app.kofipod.background.IosAiSummaryScheduler
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
        single<BillingClientPort> { IosBillingClientPort() }
        single<EntitlementCache> { IosEntitlementCache() }
        single<AiSummaryScheduler> { IosAiSummaryScheduler() }
        single<OpmlFilePort> { IosOpmlFilePort() }
    }

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.di

import app.kofipod.pro.BillingClientPort
import app.kofipod.pro.PlayBillingClientPort
import org.koin.android.ext.koin.androidApplication
import org.koin.dsl.module

/**
 * Play flavor's platform Koin bindings. Mirror of androidFoss/.../FlavorPlatformModule.kt;
 * Gradle picks exactly one based on the active flavor.
 */
val flavorPlatformModule =
    module {
        single<BillingClientPort> {
            PlayBillingClientPort(
                app = androidApplication(),
                activityHolder = get(),
            )
        }
    }

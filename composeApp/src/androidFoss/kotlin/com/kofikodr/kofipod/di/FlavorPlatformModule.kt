// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.di

import com.kofikodr.kofipod.pro.BillingClientPort
import com.kofikodr.kofipod.pro.FossBillingClientPort
import org.koin.dsl.module

/**
 * FOSS flavor's platform Koin bindings. The `play` flavor declares a same-named val
 * binding [BillingClientPort] to [PlayBillingClientPort]. Gradle picks exactly one
 * source set at build time based on the active flavor.
 */
val flavorPlatformModule =
    module {
        single<BillingClientPort> { FossBillingClientPort() }
    }

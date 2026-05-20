// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pro

/**
 * Self-build / F-Droid Pro impl: unconditional Pro(FossBuild).
 *
 * The FOSS flavor excludes Play Billing entirely (see :composeApp build.gradle.kts), so no
 * proprietary code lives in this APK. Source-builders and F-Droid users get full Pro features
 * by virtue of running this build at all.
 */
class FossBillingClientPort : BillingClientPort {
    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun queryEntitlement(): Result<ProEntitlement> = Result.success(ProEntitlement.Pro(ProSource.FossBuild))

    override suspend fun queryDisplayPrice(productId: String): Result<String?> =
        // FOSS users are already Pro — there's no purchase, so there's no price.
        // Callers display the neutral fallback copy.
        Result.success(null)

    override suspend fun launchPurchase(productId: String): Result<ProEntitlement> =
        // Already Pro; nothing to launch.
        Result.success(ProEntitlement.Pro(ProSource.FossBuild))

    override suspend fun restorePurchases(): Result<ProEntitlement> = Result.success(ProEntitlement.Pro(ProSource.FossBuild))

    override fun close() {
        // no-op
    }
}

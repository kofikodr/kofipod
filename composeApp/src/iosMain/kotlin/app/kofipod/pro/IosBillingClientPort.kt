// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

/**
 * iOS placeholder. v1 doesn't ship StoreKit; iOS users see Free until that lands.
 * Returning Free (rather than Pro(FossBuild)) is intentional — iOS is a real distribution
 * channel and treating it as "always Pro" would let iOS users access not-yet-implemented
 * surfaces and crash on missing actuals.
 */
class IosBillingClientPort : BillingClientPort {
    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun queryEntitlement(): Result<ProEntitlement> = Result.success(ProEntitlement.Free)

    override suspend fun launchPurchase(productId: String): Result<ProEntitlement> =
        Result.failure(NotImplementedError("iOS purchase flow not implemented in v1"))

    override suspend fun restorePurchases(): Result<ProEntitlement> = Result.success(ProEntitlement.Free)

    override fun close() {
        // no-op
    }
}

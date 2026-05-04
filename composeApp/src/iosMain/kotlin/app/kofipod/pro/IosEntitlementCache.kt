// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

/**
 * No-op cache for iOS. Returns null and ignores writes. Combined with [IosBillingClientPort]
 * always returning Free, this means iOS users see Free on every cold start until StoreKit
 * support ships in a future release.
 */
class IosEntitlementCache : EntitlementCache {
    override suspend fun read(): ProEntitlement? = null

    override suspend fun write(entitlement: ProEntitlement) {
        // intentionally no-op
    }

    override suspend fun clear() {
        // intentionally no-op
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

/**
 * Device-local cache of the last verified Pro entitlement reading.
 *
 * Backed by a backup-excluded SharedPreferences file on Android so a device-clone or
 * new-device restore cannot resurrect a stale "Pro" state. iOS impl is a no-op until
 * StoreKit lands.
 *
 * The cache is a UX optimisation, not a security boundary. Real entitlement always
 * re-verifies via [BillingClientPort.queryEntitlement] / [BillingClientPort.restorePurchases]
 * on every cold start — see [ProEntitlementRepository.refreshOnStart].
 */
interface EntitlementCache {
    /** Returns the last cached entitlement, or null if never written / iOS no-op. */
    suspend fun read(): ProEntitlement?

    /** Persists [entitlement]. Calls with [ProEntitlement.Unknown] are silently ignored. */
    suspend fun write(entitlement: ProEntitlement)

    /** Clears any cached value. */
    suspend fun clear()
}

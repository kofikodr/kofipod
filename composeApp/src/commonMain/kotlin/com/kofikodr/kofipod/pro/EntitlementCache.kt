// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pro

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
    /**
     * Returns the last cached entitlement, or null if never written / iOS no-op.
     *
     * If the reviewer-unlock flag is set (see [setReviewerUnlocked]), this returns
     * [ProEntitlement.Pro] with [ProSource.ReviewerUnlock] regardless of the
     * underlying billing tier — a Play Billing "Free" reading cannot clobber a
     * reviewer-unlocked device. Clearing the flag (or [clear]) restores normal
     * behaviour.
     */
    suspend fun read(): ProEntitlement?

    /**
     * Persists the billing-derived [entitlement]. Never touches the reviewer-unlock
     * flag. Calls with [ProEntitlement.Unknown] are silently ignored.
     */
    suspend fun write(entitlement: ProEntitlement)

    /** Returns true if the reviewer-unlock flag is currently set. */
    suspend fun isReviewerUnlocked(): Boolean

    /**
     * Sets or clears the sticky reviewer-unlock flag. Survives billing refreshes;
     * only [clear] or an explicit `setReviewerUnlocked(false)` removes it.
     */
    suspend fun setReviewerUnlocked(unlocked: Boolean)

    /** Clears the cached tier AND the reviewer-unlock flag. */
    suspend fun clear()
}

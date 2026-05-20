// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pro

/**
 * Platform-agnostic billing surface used by [ProEntitlementRepository].
 *
 * Three implementations:
 * - `androidPlay/.../PlayBillingClientPort.kt` — real Google Play Billing v7+ wrapper.
 * - `androidFoss/.../FossBillingClientPort.kt` — unconditional `Pro(FossBuild)`.
 * - `iosMain/.../IosBillingClientPort.kt` — `Free` stub until iOS becomes a focus.
 *
 * The port models a long-lived service: [connect] starts the underlying client (idempotent),
 * [close] tears it down. Repository owns the connect/close lifecycle.
 *
 * Purchase flow lives behind [launchPurchase] which takes no host parameter — Android
 * implementations resolve the current foreground Activity via the `ActivityHolder` registry
 * in `androidMain`. iOS / FOSS implementations don't need a host.
 */
interface BillingClientPort {
    /** Connects the underlying billing client. Safe to call repeatedly; resolves to Unit on success. */
    suspend fun connect(): Result<Unit>

    /** Returns the current entitlement reading. Must not return [ProEntitlement.Unknown]. */
    suspend fun queryEntitlement(): Result<ProEntitlement>

    /**
     * Returns the platform-formatted, locale-aware display price for [productId]
     * (e.g. `"$12.99"`, `"€10.99"`, `"₹999.00"`) so the paywall can render the
     * price Play would actually charge instead of a hard-coded string. Wraps
     * `Result.success(null)` when the platform either has no concept of a
     * displayed price (FOSS / iOS placeholder) or didn't return one (Play
     * billing query returned no product details). Wraps `Result.failure` only
     * on transport-level errors; callers should treat both null and failure as
     * "show neutral fallback copy" — neither is an exceptional condition.
     *
     * Caller must have invoked [connect] first — this method does not connect
     * on its own, matching the existing convention of [queryEntitlement] and
     * [restorePurchases]. The repository wraps the connect-then-query pair.
     */
    suspend fun queryDisplayPrice(productId: String): Result<String?>

    /**
     * Launches the platform purchase flow for [productId]. Suspends until the user completes
     * or cancels the dialog. Result is the new entitlement reading; on cancel, returns the
     * pre-purchase reading (typically [ProEntitlement.Free]).
     */
    suspend fun launchPurchase(productId: String): Result<ProEntitlement>

    /**
     * Re-queries Play Billing for any historical purchases on this account. Used by Settings
     * "Restore Purchase" and by cold-start auto-restore in [ProEntitlementRepository].
     */
    suspend fun restorePurchases(): Result<ProEntitlement>

    /** Tears down the underlying client. Safe to call after [connect] failure. */
    fun close()
}

/**
 * Product IDs declared in Play Console. Single source of truth so UI / repo / port agree.
 */
object ProProducts {
    const val INDIVIDUAL = "kofipod_pro"
}

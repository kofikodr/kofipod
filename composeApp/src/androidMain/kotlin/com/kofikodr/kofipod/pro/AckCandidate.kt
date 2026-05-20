// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pro

/**
 * Mirror of the fields we care about on `com.android.billingclient.api.Purchase`
 * with no dependency on Play Billing types. Lets the
 * [unacknowledgedIndividualTokens] selector live in `androidMain` and be
 * unit-tested from `androidUnitTest` without dragging Play Billing onto the
 * FOSS-flavor classpath.
 *
 * `androidPlay/.../PlayBillingClientPort` maps `Purchase` → [AckCandidate]
 * at the boundary (one-liner; not worth its own test).
 */
internal data class AckCandidate(
    val productIds: List<String>,
    val isPurchased: Boolean,
    val isAcknowledged: Boolean,
    val purchaseToken: String,
)

/**
 * Returns purchase tokens of [ProProducts.INDIVIDUAL] entitlements that were
 * granted (`isPurchased`) but never acknowledged. Play auto-refunds and
 * revokes the entitlement ~3 days after the purchase callback fires if the
 * token is never acknowledged via `BillingClient.acknowledgePurchase`, so
 * every PURCHASED token in this list MUST be acknowledged before the user
 * is treated as Pro durably.
 *
 * Empty list means either there is no individual purchase at all OR every
 * existing one is already acknowledged — both correct termination states.
 */
internal fun unacknowledgedIndividualTokens(candidates: List<AckCandidate>): List<String> =
    candidates
        .filter { ProProducts.INDIVIDUAL in it.productIds }
        .filter { it.isPurchased && !it.isAcknowledged }
        .map { it.purchaseToken }

// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pro

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [unacknowledgedIndividualTokens]'s contract. The selector decides which
 * Play Billing purchase tokens MUST be acknowledged before the user is treated
 * as Pro durably. A regression that returns the wrong subset has real-money
 * consequences:
 *
 *  - Missing a real PURCHASED+unacked token → Play auto-refunds after the
 *    ack window, user pays and silently loses Pro.
 *  - Including an already-acked token → we'd re-ack which Play treats as
 *    an OK no-op, but the BillingException path would still fire on a transient
 *    second-ack failure, so over-inclusion still has UX cost.
 *  - Including a non-INDIVIDUAL or PENDING purchase → we'd ack something we
 *    haven't decided to grant Pro for; harmless for one-time SKUs (we have
 *    only one) but the discipline should hold if a second SKU is added.
 */
class AckCandidateTest {
    @Test
    fun returnsEmpty_whenNoPurchases() {
        assertEquals(emptyList(), unacknowledgedIndividualTokens(emptyList()))
    }

    @Test
    fun returnsEmpty_whenAllAlreadyAcknowledged() {
        val candidates =
            listOf(
                AckCandidate(
                    productIds = listOf(ProProducts.INDIVIDUAL),
                    isPurchased = true,
                    isAcknowledged = true,
                    purchaseToken = "token-already-acked",
                ),
            )
        assertEquals(
            emptyList(),
            unacknowledgedIndividualTokens(candidates),
            "Already-acked purchases must not be returned; re-acking is harmless but unnecessary work",
        )
    }

    @Test
    fun returnsToken_forSingleUnackedIndividualPurchase() {
        val candidates =
            listOf(
                AckCandidate(
                    productIds = listOf(ProProducts.INDIVIDUAL),
                    isPurchased = true,
                    isAcknowledged = false,
                    purchaseToken = "needs-ack",
                ),
            )
        assertEquals(listOf("needs-ack"), unacknowledgedIndividualTokens(candidates))
    }

    @Test
    fun skipsPendingPurchase() {
        // Play's "pending" state (e.g., cash payment at convenience store)
        // must NOT be acknowledged — Play rejects ack on pending purchases.
        // We classify the same way and skip the token until it transitions
        // to PURCHASED.
        val candidates =
            listOf(
                AckCandidate(
                    productIds = listOf(ProProducts.INDIVIDUAL),
                    isPurchased = false,
                    isAcknowledged = false,
                    purchaseToken = "still-pending",
                ),
            )
        assertEquals(emptyList(), unacknowledgedIndividualTokens(candidates))
    }

    @Test
    fun skipsUnpurchasedEvenWhenAcknowledged() {
        // Defensive isolation of the `isPurchased` clause from `!isAcknowledged`.
        // A refactor that flipped `&&` to `||` would accept this candidate; the
        // pending-but-already-acked combination is implausible from Play but
        // pins the AND semantics so the gate can't silently soften.
        val candidates =
            listOf(
                AckCandidate(
                    productIds = listOf(ProProducts.INDIVIDUAL),
                    isPurchased = false,
                    isAcknowledged = true,
                    purchaseToken = "pending-and-acked",
                ),
            )
        assertEquals(emptyList(), unacknowledgedIndividualTokens(candidates))
    }

    @Test
    fun skipsCandidateWithNoProductIds() {
        // Guard against a future Purchase → AckCandidate mapping bug that
        // drops the productIds list. A token with no product IDs cannot be
        // matched to INDIVIDUAL and must not be acked silently — the right
        // outcome is to drop it here and let a real-world Purchase with the
        // correct productIds drive the ack path.
        val candidates =
            listOf(
                AckCandidate(
                    productIds = emptyList(),
                    isPurchased = true,
                    isAcknowledged = false,
                    purchaseToken = "orphan",
                ),
            )
        assertEquals(emptyList(), unacknowledgedIndividualTokens(candidates))
    }

    @Test
    fun skipsNonIndividualProduct() {
        // A future SKU (e.g., kofipod_pro_family) must not be acked through
        // the individual gate — its acknowledgement belongs to whatever code
        // owns that entitlement. Pin the discipline so adding a SKU doesn't
        // accidentally route through this selector.
        val candidates =
            listOf(
                AckCandidate(
                    productIds = listOf("kofipod_pro_family"),
                    isPurchased = true,
                    isAcknowledged = false,
                    purchaseToken = "wrong-sku",
                ),
            )
        assertEquals(emptyList(), unacknowledgedIndividualTokens(candidates))
    }

    @Test
    fun returnsTokens_forMixedAcknowledgmentStateSamePurchaseList() {
        // A user could have one fresh purchase + one already-acked historical
        // purchase from a restore. Only the fresh one needs work.
        val candidates =
            listOf(
                AckCandidate(
                    productIds = listOf(ProProducts.INDIVIDUAL),
                    isPurchased = true,
                    isAcknowledged = true,
                    purchaseToken = "historical-acked",
                ),
                AckCandidate(
                    productIds = listOf(ProProducts.INDIVIDUAL),
                    isPurchased = true,
                    isAcknowledged = false,
                    purchaseToken = "new-unacked",
                ),
            )
        assertEquals(listOf("new-unacked"), unacknowledgedIndividualTokens(candidates))
    }

    @Test
    fun multiProductPurchase_matchesIfIndividualPresent() {
        // Play allows a Purchase object to list multiple productIds. If our
        // INDIVIDUAL is in the list, we still ack — the other products may
        // belong to a future bundle.
        val candidates =
            listOf(
                AckCandidate(
                    productIds = listOf("kofipod_unrelated", ProProducts.INDIVIDUAL),
                    isPurchased = true,
                    isAcknowledged = false,
                    purchaseToken = "bundle-token",
                ),
            )
        assertEquals(listOf("bundle-token"), unacknowledgedIndividualTokens(candidates))
    }

    @Test
    fun preservesOrder_forMultipleUnackedTokens() {
        // Ack ordering matters indirectly — if the callback chain fails partway
        // through, the next refresh sees the same input list. Pinning order so
        // a refactor that sorts/dedupes by chance doesn't drift retry semantics.
        val candidates =
            listOf(
                AckCandidate(listOf(ProProducts.INDIVIDUAL), true, false, "first"),
                AckCandidate(listOf(ProProducts.INDIVIDUAL), true, false, "second"),
                AckCandidate(listOf(ProProducts.INDIVIDUAL), true, false, "third"),
            )
        assertEquals(listOf("first", "second", "third"), unacknowledgedIndividualTokens(candidates))
    }

    @Test
    fun productIdMustBeExactMatch_notPrefixInEitherDirection() {
        // ProProducts.INDIVIDUAL = "kofipod_pro". To falsify both possible
        // broken implementations, this candidate list contains:
        //   - "kofipod_pr"     — a true prefix of INDIVIDUAL. A buggy
        //                        `productIds.any { INDIVIDUAL.startsWith(it) }`
        //                        would let this through.
        //   - "kofipod_pro_v2" — a string INDIVIDUAL is a prefix of. A buggy
        //                        `productIds.any { it.startsWith(INDIVIDUAL) }`
        //                        would let this through.
        //   - "kofipod_pro"    — the exact match; this one MUST be returned.
        // Only an equality-match impl returns exactly ["exact-token"].
        val candidates =
            listOf(
                AckCandidate(
                    productIds = listOf("kofipod_pr"),
                    isPurchased = true,
                    isAcknowledged = false,
                    purchaseToken = "shorter-prefix-token",
                ),
                AckCandidate(
                    productIds = listOf("kofipod_pro_v2"),
                    isPurchased = true,
                    isAcknowledged = false,
                    purchaseToken = "longer-suffix-token",
                ),
                AckCandidate(
                    productIds = listOf(ProProducts.INDIVIDUAL),
                    isPurchased = true,
                    isAcknowledged = false,
                    purchaseToken = "exact-token",
                ),
            )
        assertEquals(
            listOf("exact-token"),
            unacknowledgedIndividualTokens(candidates),
            "Equality match: only the exact 'kofipod_pro' token; prefixes in either direction excluded",
        )
    }

    @Test
    fun blankPurchaseToken_passesThrough_contractIsCallerValidates() {
        // Contract decision: the selector does NOT defend against blank/
        // empty tokens — that's a malformed Purchase the upstream mapping
        // should never emit. If it ever does, BillingClient.acknowledgePurchase
        // will fail at the Play API boundary, which is the right place to
        // surface the error (we'd lose the BillingException message context
        // if we silently dropped here). Pin this so a "helpful" future filter
        // that drops blanks here doesn't mask a real bug.
        val candidates =
            listOf(
                AckCandidate(
                    productIds = listOf(ProProducts.INDIVIDUAL),
                    isPurchased = true,
                    isAcknowledged = false,
                    purchaseToken = "",
                ),
            )
        assertEquals(listOf(""), unacknowledgedIndividualTokens(candidates))
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pro

import kotlinx.coroutines.CompletableDeferred

/**
 * In-memory [EntitlementCache] for tests. Mirrors [com.kofikodr.kofipod.pro.AndroidEntitlementCache]'s
 * read-time overlay: when the reviewer flag is set, [read] returns
 * [ProEntitlement.Pro] with [ProSource.ReviewerUnlock] regardless of [stored].
 */
internal class FakeEntitlementCache(
    initial: ProEntitlement?,
    initialReviewerUnlock: Boolean = false,
) : EntitlementCache {
    private var stored: ProEntitlement? = initial
    private var reviewerUnlock: Boolean = initialReviewerUnlock

    override suspend fun read(): ProEntitlement? = if (reviewerUnlock) ProEntitlement.Pro(ProSource.ReviewerUnlock) else stored

    override suspend fun write(entitlement: ProEntitlement) {
        if (entitlement !is ProEntitlement.Unknown) stored = entitlement
    }

    override suspend fun isReviewerUnlocked(): Boolean = reviewerUnlock

    override suspend fun setReviewerUnlocked(unlocked: Boolean) {
        reviewerUnlock = unlocked
    }

    override suspend fun clear() {
        stored = null
        reviewerUnlock = false
    }
}

internal class FakeBillingClientPort(
    private val connect: Result<Unit> = Result.success(Unit),
    private val query: Result<ProEntitlement> = Result.success(ProEntitlement.Free),
    private val restore: Result<ProEntitlement> = Result.success(ProEntitlement.Free),
    private val purchase: Result<ProEntitlement> = Result.success(ProEntitlement.Pro(ProSource.Individual)),
    private val queryGate: CompletableDeferred<Unit>? = null,
) : BillingClientPort {
    var connectCalls = 0
        private set
    var queryCalls = 0
        private set
    var restoreCalls = 0
        private set
    var purchaseCalls = 0
        private set
    var closeCalls = 0
        private set

    override suspend fun connect(): Result<Unit> {
        connectCalls++
        return connect
    }

    override suspend fun queryEntitlement(): Result<ProEntitlement> {
        queryCalls++
        queryGate?.await()
        return query
    }

    override suspend fun launchPurchase(productId: String): Result<ProEntitlement> {
        purchaseCalls++
        return purchase
    }

    override suspend fun restorePurchases(): Result<ProEntitlement> {
        restoreCalls++
        return restore
    }

    override fun close() {
        closeCalls++
    }
}

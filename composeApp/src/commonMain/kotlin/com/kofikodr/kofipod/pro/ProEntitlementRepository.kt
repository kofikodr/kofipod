// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pro

import com.kofikodr.kofipod.crypto.constantTimeHexEquals
import com.kofikodr.kofipod.crypto.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val LOG_TAG = "Kofipod-Pro"

/**
 * Owns the single [StateFlow] of [ProEntitlement] for the running user.
 *
 * Lifecycle:
 * - Construction: state = [ProEntitlement.Unknown]. No port calls.
 * - [hydrateFromCache] (called eagerly in [refreshOnStart], also exposed for tests): if the
 *   [EntitlementCache] has a value, emit it immediately so cold-start UI doesn't render
 *   "Unknown" while waiting for billing.
 * - [refreshOnStart]: connect the port, query, write the cache. Single-flight via [refreshLock]
 *   so concurrent app starts / activity recreations don't pile up duplicate queries.
 * - [restorePurchases]: explicit user-triggered re-query (Settings button + cold start). Same
 *   single-flight semantics.
 * - [launchPurchase]: forwards to the port. Result becomes the new state via the same path.
 */
class ProEntitlementRepository(
    private val cache: EntitlementCache,
    private val port: BillingClientPort,
    private val appScope: CoroutineScope,
    /**
     * SHA-256 (lower-case hex, 64 chars) of the reviewer unlock code. Production
     * reads this from [ReviewerUnlockConfig.hash]; tests inject a known
     * hash. Empty string disables [applyReviewerUnlock] entirely.
     */
    private val reviewerUnlockHash: String = ReviewerUnlockConfig.hash,
) {
    private val _state = MutableStateFlow<ProEntitlement>(ProEntitlement.Unknown)
    val state: StateFlow<ProEntitlement> = _state.asStateFlow()

    private val refreshLock = Mutex()
    private var inflightRefresh: Job? = null

    suspend fun hydrateFromCache() {
        val cached = cache.read() ?: return
        _state.value = cached
    }

    suspend fun refreshOnStart() {
        hydrateFromCache()

        val job =
            refreshLock.withLock {
                val existing = inflightRefresh
                if (existing != null && existing.isActive) {
                    existing
                } else {
                    val started = appScope.launch { runQueryAndApply() }
                    inflightRefresh = started
                    started
                }
            }
        job.join()
    }

    suspend fun restorePurchases(): Result<ProEntitlement> =
        appScope
            .async {
                val connected = port.connect()
                if (connected.isFailure) {
                    println("$LOG_TAG: restorePurchases connect failed: ${connected.exceptionOrNull()?.message}")
                    return@async Result.failure<ProEntitlement>(
                        connected.exceptionOrNull() ?: RuntimeException("connect"),
                    )
                }
                val result = port.restorePurchases()
                result.onSuccess { applyResult(it) }
                result
            }.await()

    /**
     * Best-effort fetch of the platform-formatted display price for [productId]
     * (e.g. `"$12.99"`). Returns null when the port couldn't connect, the query
     * failed, or the platform has no price to share (FOSS / iOS). The paywall
     * substitutes neutral copy on null; we deliberately do not raise an error
     * here — the paywall must still be usable (the actual price is also shown
     * inside the Play purchase sheet that opens on tap).
     */
    suspend fun fetchDisplayPrice(productId: String): String? {
        val connected = port.connect()
        if (connected.isFailure) return null
        return port.queryDisplayPrice(productId).getOrNull()
    }

    suspend fun launchPurchase(productId: String): Result<ProEntitlement> {
        val connected = port.connect()
        if (connected.isFailure) {
            return Result.failure(connected.exceptionOrNull() ?: RuntimeException("connect"))
        }
        val result = port.launchPurchase(productId)
        result.onSuccess { applyResult(it) }
        return result
    }

    private suspend fun runQueryAndApply() {
        val connected = port.connect()
        if (connected.isFailure) {
            println("$LOG_TAG: refreshOnStart connect failed: ${connected.exceptionOrNull()?.message}")
            return
        }
        val queried = port.queryEntitlement()
        queried.fold(
            onSuccess = { applyResult(it) },
            onFailure = { println("$LOG_TAG: refreshOnStart query failed: ${it.message}") },
        )
    }

    private suspend fun applyResult(reading: ProEntitlement) {
        val safe =
            if (reading is ProEntitlement.Unknown) {
                println("$LOG_TAG: port returned Unknown — coercing to Free")
                ProEntitlement.Free
            } else {
                reading
            }
        cache.write(safe)
        // Reviewer unlock takes precedence over any billing reading. Without this
        // overlay, a Play Billing "Free" answer on cold start would visibly downgrade
        // a reviewer who already entered the unlock code on this device.
        _state.value =
            if (cache.isReviewerUnlocked()) ProEntitlement.Pro(ProSource.ReviewerUnlock) else safe
    }

    /**
     * Hidden reviewer affordance: validates [code] against the SHA-256 hash baked
     * into the binary at build time ([ReviewerUnlockConfig.hash]). On match,
     * sets the sticky cache flag and emits [ProEntitlement.Pro] with
     * [ProSource.ReviewerUnlock].
     *
     * Returns true on successful unlock, false on hash mismatch or when the build
     * has no hash configured (empty string disables the affordance entirely — e.g.
     * release builds for non-Play distribution can opt out by leaving the hash blank).
     *
     * Comparison is constant-time to avoid leaking byte-prefix matches via timing.
     */
    suspend fun applyReviewerUnlock(code: String): Boolean {
        if (reviewerUnlockHash.isBlank()) return false
        val actual = sha256Hex(code)
        if (!constantTimeHexEquals(actual, reviewerUnlockHash)) return false
        cache.setReviewerUnlocked(true)
        _state.value = ProEntitlement.Pro(ProSource.ReviewerUnlock)
        return true
    }
}

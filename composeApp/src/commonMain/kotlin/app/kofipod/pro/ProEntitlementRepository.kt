// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

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
        _state.value = safe
        cache.write(safe)
    }
}

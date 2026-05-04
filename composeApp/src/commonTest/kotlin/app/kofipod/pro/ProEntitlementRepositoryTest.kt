// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProEntitlementRepositoryTest {
    // Unconfined so coroutines launched on appScope run inline on the calling thread,
    // which makes concurrency assertions deterministic instead of depending on real-thread timing.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `initial state is Unknown when cache empty`() =
        runTest {
            val cache = FakeEntitlementCache(initial = null)
            val port = FakeBillingClientPort(query = Result.success(ProEntitlement.Free))
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            assertEquals(ProEntitlement.Unknown, repo.state.value)
        }

    @Test
    fun `initial state is cached value when cache has reading`() =
        runTest {
            val cache = FakeEntitlementCache(initial = ProEntitlement.Pro(ProSource.Individual))
            val port = FakeBillingClientPort()
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            // Trigger the lazy hydrate:
            repo.hydrateFromCache()

            assertEquals(ProEntitlement.Pro(ProSource.Individual), repo.state.value)
        }

    @Test
    fun `refreshOnStart connects, queries, writes cache, updates state`() =
        runTest {
            val cache = FakeEntitlementCache(initial = null)
            val port =
                FakeBillingClientPort(
                    query = Result.success(ProEntitlement.Pro(ProSource.Family)),
                )
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            repo.refreshOnStart()

            assertEquals(1, port.connectCalls)
            assertEquals(1, port.queryCalls)
            assertEquals(ProEntitlement.Pro(ProSource.Family), repo.state.value)
            assertEquals(ProEntitlement.Pro(ProSource.Family), cache.read())
        }

    @Test
    fun `refreshOnStart on query failure keeps cached state`() =
        runTest {
            val cache = FakeEntitlementCache(initial = ProEntitlement.Pro(ProSource.Individual))
            val port = FakeBillingClientPort(query = Result.failure(RuntimeException("net")))
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            repo.hydrateFromCache()
            repo.refreshOnStart()

            assertEquals(ProEntitlement.Pro(ProSource.Individual), repo.state.value)
            // Cache stays as it was — failure does not write.
            assertEquals(ProEntitlement.Pro(ProSource.Individual), cache.read())
        }

    @Test
    fun `refreshOnStart connect failure preserves cached state`() =
        runTest {
            val cache = FakeEntitlementCache(initial = ProEntitlement.Pro(ProSource.Individual))
            val port =
                FakeBillingClientPort(
                    connect = Result.failure(RuntimeException("billing unavailable")),
                )
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            repo.refreshOnStart()

            assertEquals(ProEntitlement.Pro(ProSource.Individual), repo.state.value)
            assertEquals(ProEntitlement.Pro(ProSource.Individual), cache.read())
            assertEquals(0, port.queryCalls)
        }

    @Test
    fun `restorePurchases calls port restore + writes cache`() =
        runTest {
            val cache = FakeEntitlementCache(initial = ProEntitlement.Free)
            val port =
                FakeBillingClientPort(
                    restore = Result.success(ProEntitlement.Pro(ProSource.Individual)),
                )
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            val result = repo.restorePurchases()

            assertTrue(result.isSuccess)
            assertEquals(ProEntitlement.Pro(ProSource.Individual), repo.state.value)
            assertEquals(ProEntitlement.Pro(ProSource.Individual), cache.read())
        }

    @Test
    fun `restorePurchases failure leaves state and cache unchanged`() =
        runTest {
            val cache = FakeEntitlementCache(initial = ProEntitlement.Free)
            val port =
                FakeBillingClientPort(
                    restore = Result.failure(RuntimeException("network")),
                )
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)
            repo.hydrateFromCache() // sets state to Free

            val result = repo.restorePurchases()

            assertTrue(result.isFailure)
            assertEquals(ProEntitlement.Free, repo.state.value)
            assertEquals(ProEntitlement.Free, cache.read())
        }

    @Test
    fun `launchPurchase success updates state and writes cache`() =
        runTest {
            val cache = FakeEntitlementCache(initial = ProEntitlement.Free)
            val port =
                FakeBillingClientPort(
                    purchase = Result.success(ProEntitlement.Pro(ProSource.Individual)),
                )
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            val result = repo.launchPurchase(ProProducts.INDIVIDUAL)

            assertTrue(result.isSuccess)
            assertEquals(ProEntitlement.Pro(ProSource.Individual), repo.state.value)
            assertEquals(ProEntitlement.Pro(ProSource.Individual), cache.read())
            assertEquals(1, port.connectCalls)
            assertEquals(1, port.purchaseCalls)
        }

    @Test
    fun `launchPurchase returns failure when connect fails`() =
        runTest {
            val cache = FakeEntitlementCache(initial = null)
            val port =
                FakeBillingClientPort(
                    connect = Result.failure(RuntimeException("billing unavailable")),
                )
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            val result = repo.launchPurchase(ProProducts.INDIVIDUAL)

            assertTrue(result.isFailure)
            assertEquals(ProEntitlement.Unknown, repo.state.value)
            assertEquals(0, port.purchaseCalls)
        }

    @Test
    fun `concurrent refreshOnStart calls coalesce`() =
        runTest {
            // Gate the first query mid-execution so the second caller is forced to enter the
            // refresh mutex while the first job is still in-flight. This proves real coalescing
            // (single shared Job) rather than sequential serialization.
            val gate = CompletableDeferred<Unit>()
            val cache = FakeEntitlementCache(initial = null)
            val port =
                FakeBillingClientPort(
                    query = Result.success(ProEntitlement.Free),
                    queryGate = gate,
                )
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            val a = scope.launch { repo.refreshOnStart() }
            // Let refresh A enter queryEntitlement and suspend on the gate.
            yield()
            yield()
            val b = scope.launch { repo.refreshOnStart() }
            yield()
            yield()

            // At this point A is suspended in queryEntitlement; B should have observed the
            // in-flight Job and joined it instead of starting its own pipeline.
            gate.complete(Unit)
            a.join()
            b.join()

            assertEquals(1, port.connectCalls)
            assertEquals(1, port.queryCalls)
        }

    @Test
    fun `port returning Unknown is a contract violation but does not corrupt state`() =
        runTest {
            val cache = FakeEntitlementCache(initial = null)
            val port = FakeBillingClientPort(query = Result.success(ProEntitlement.Unknown))
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            repo.refreshOnStart()

            assertIs<ProEntitlement.Free>(repo.state.value)
        }
}

// -- fakes ---------------------------------------------------------------------------------------

private class FakeEntitlementCache(initial: ProEntitlement?) : EntitlementCache {
    private var stored: ProEntitlement? = initial

    override suspend fun read(): ProEntitlement? = stored

    override suspend fun write(entitlement: ProEntitlement) {
        if (entitlement !is ProEntitlement.Unknown) stored = entitlement
    }

    override suspend fun clear() {
        stored = null
    }
}

private class FakeBillingClientPort(
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

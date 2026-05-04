// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProEntitlementRepositoryTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
    fun `concurrent refreshOnStart calls coalesce`() =
        runTest {
            val cache = FakeEntitlementCache(initial = null)
            val port = FakeBillingClientPort(query = Result.success(ProEntitlement.Free))
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            // Fire two concurrent refresh calls — the second should not trigger a second query.
            coroutineScope {
                launch { repo.refreshOnStart() }
                launch { repo.refreshOnStart() }
            }

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

    @Test
    fun `state flow is hot StateFlow`() =
        runTest {
            val cache = FakeEntitlementCache(initial = null)
            val port = FakeBillingClientPort()
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            val first = repo.state.first()
            assertEquals(ProEntitlement.Unknown, first)
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

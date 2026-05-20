// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pro

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
                    query = Result.success(ProEntitlement.Pro(ProSource.Individual)),
                )
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            repo.refreshOnStart()

            assertEquals(1, port.connectCalls)
            assertEquals(1, port.queryCalls)
            assertEquals(ProEntitlement.Pro(ProSource.Individual), repo.state.value)
            assertEquals(ProEntitlement.Pro(ProSource.Individual), cache.read())
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

    @Test
    fun `fetchDisplayPrice returns formatted price when port returns one`() =
        runTest {
            // The exact string is opaque to us — Play returns a locale-formatted
            // amount like "$12.99" or "€10.99". The test just pins that whatever
            // the port returns lands on the caller verbatim.
            val cache = FakeEntitlementCache(initial = null)
            val port = FakeBillingClientPort(displayPrice = Result.success("€10.99"))
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            assertEquals("€10.99", repo.fetchDisplayPrice(ProProducts.INDIVIDUAL))
            assertEquals(1, port.connectCalls)
            assertEquals(1, port.displayPriceCalls)
            // Pin that the productId reaches the port verbatim — confused-deputy
            // risk in billing code: the wrong product ID would charge the user the
            // wrong amount in the purchase sheet that opens on tap.
            assertEquals(ProProducts.INDIVIDUAL, port.lastDisplayPriceProductId)
        }

    @Test
    fun `fetchDisplayPrice returns null when port returns no price`() =
        runTest {
            // FOSS / iOS ports unconditionally return success(null); also the
            // success(null) path is what Play returns when the product details
            // query came back empty.
            val cache = FakeEntitlementCache(initial = null)
            val port = FakeBillingClientPort(displayPrice = Result.success(null))
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            assertEquals(null, repo.fetchDisplayPrice(ProProducts.INDIVIDUAL))
            assertEquals(1, port.connectCalls)
            assertEquals(1, port.displayPriceCalls)
        }

    @Test
    fun `fetchDisplayPrice returns null when connect fails`() =
        runTest {
            // A failed connect must not throw out of the call — the paywall has
            // to render with neutral copy in this case. Also pins that we do not
            // call queryDisplayPrice when connect failed (no point) — slightly
            // tightens the contract beyond what the kdoc requires.
            val cache = FakeEntitlementCache(initial = null)
            val port = FakeBillingClientPort(connect = Result.failure(RuntimeException("no service")))
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            assertEquals(null, repo.fetchDisplayPrice(ProProducts.INDIVIDUAL))
            assertEquals(1, port.connectCalls)
            assertEquals(0, port.displayPriceCalls)
        }

    @Test
    fun `fetchDisplayPrice returns null when port query fails`() =
        runTest {
            // Transport-level failure (e.g. Play Billing returned a non-OK
            // response code) must funnel into "no price" rather than an
            // exception bubbling into the paywall init block.
            val cache = FakeEntitlementCache(initial = null)
            val port = FakeBillingClientPort(displayPrice = Result.failure(RuntimeException("net")))
            val repo = ProEntitlementRepository(cache = cache, port = port, appScope = scope)

            assertEquals(null, repo.fetchDisplayPrice(ProProducts.INDIVIDUAL))
            assertEquals(1, port.connectCalls)
            assertEquals(1, port.displayPriceCalls)
        }
}

// Fakes are extracted to ProTestFakes.kt so ReviewerUnlockTest can share them.

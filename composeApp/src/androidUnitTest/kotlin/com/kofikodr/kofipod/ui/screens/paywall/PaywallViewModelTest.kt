// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.paywall

import com.kofikodr.kofipod.pro.BillingClientPort
import com.kofikodr.kofipod.pro.EntitlementCache
import com.kofikodr.kofipod.pro.PaywallRouter
import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.pro.ProEntitlementRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Regression tests for [PaywallViewModel]'s in-flight guard (issue #29).
 *
 * The bug: `launchPurchase`/`restore` had no in-flight guard and flipped `mode` INSIDE the
 * launched coroutine. A double tap launched two coroutines that both called
 * `repo.launchPurchase`, and the Play Billing port's single `purchaseContinuation` was
 * overwritten by the second — leaking the first (stuck coroutine / lost result). The fix
 * flips `mode` synchronously before the launch and ignores taps while `mode != Idle`.
 *
 * [StandardTestDispatcher] as Main is load-bearing: a `viewModelScope.launch {}` body is
 * only QUEUED (not run) until the scheduler advances — exactly the window in which two
 * rapid taps would both have observed `mode == Idle` under the old (in-coroutine) flip.
 * The real [PaywallViewModel] + real [ProEntitlementRepository] are under test; only the
 * billing port and cache are hand-written fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaywallViewModelTest {
    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.newViewModel(port: RecordingBillingPort): PaywallViewModel {
        val repo =
            ProEntitlementRepository(
                cache = NoOpEntitlementCache(),
                port = port,
                // Same StandardTestDispatcher family as Main (shared testScheduler) so
                // `restorePurchases`, which delegates to `appScope.async{}`, advances under
                // the same deterministic clock as the viewModelScope launch{} bodies —
                // `advanceUntilIdle()` drains both. Symmetric on purpose: no eager-vs-queued
                // asymmetry between the launchPurchase (inline) and restore (appScope) paths.
                appScope = CoroutineScope(StandardTestDispatcher(testScheduler)),
                reviewerUnlockHash = "",
            )
        return PaywallViewModel(repo, PaywallRouter())
    }

    @Test
    fun doubleTapPurchase_launchesBillingFlowOnce() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val port = RecordingBillingPort()
            val vm = newViewModel(port)

            // Two rapid taps before the first coroutine has a chance to run.
            vm.purchaseIndividual()
            vm.purchaseIndividual()
            advanceUntilIdle()

            assertEquals(
                1,
                port.launchPurchaseCalls,
                "a double tap must launch the billing flow exactly once — the second tap must be " +
                    "ignored while mode == Launching, so the port's purchaseContinuation isn't overwritten",
            )
        }

    @Test
    fun purchase_isAllowedAgain_afterThePreviousOneCompletes() =
        runTest {
            // Complements the double-tap test: verifies the guard RELEASES after completion so a
            // later legitimate tap isn't blocked. This does not discriminate the original #29 bug
            // (it passes on both old and new code); it guards against over-guarding (a stuck mode).
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val port = RecordingBillingPort()
            val vm = newViewModel(port)

            vm.purchaseIndividual()
            advanceUntilIdle() // first purchase runs to completion → mode returns to Idle

            vm.purchaseIndividual()
            advanceUntilIdle()

            assertEquals(
                2,
                port.launchPurchaseCalls,
                "the guard must release once the prior purchase completes — a later tap is allowed",
            )
        }

    @Test
    fun doubleTapRestore_queriesPurchasesOnce() =
        runTest {
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val port = RecordingBillingPort()
            val vm = newViewModel(port)

            vm.restore()
            vm.restore()
            advanceUntilIdle()

            assertEquals(1, port.restoreCalls, "a double tap on Restore must query purchases exactly once")
        }

    @Test
    fun restore_whileAPurchaseIsLaunching_isIgnored() =
        runTest {
            // The guard is `mode != Idle`, so it's cross-cutting: a Restore tap while a purchase
            // is in flight (mode == Launching) must be ignored, and vice versa — both operations
            // target the same single-continuation billing port.
            Dispatchers.setMain(StandardTestDispatcher(testScheduler))
            val port = RecordingBillingPort()
            val vm = newViewModel(port)

            vm.purchaseIndividual() // mode → Launching (synchronously)
            vm.restore() // must be ignored while Launching
            advanceUntilIdle()

            assertEquals(1, port.launchPurchaseCalls, "the purchase still launches once")
            assertEquals(0, port.restoreCalls, "restore must not run while a purchase is launching")
        }
}

/** Records billing calls so a test can assert how many times each was invoked. */
private class RecordingBillingPort : BillingClientPort {
    var launchPurchaseCalls = 0
        private set
    var restoreCalls = 0
        private set

    override suspend fun connect(): Result<Unit> = Result.success(Unit)

    override suspend fun queryEntitlement(): Result<ProEntitlement> = Result.success(ProEntitlement.Free)

    override suspend fun queryDisplayPrice(productId: String): Result<String?> = Result.success(null)

    override suspend fun launchPurchase(productId: String): Result<ProEntitlement> {
        launchPurchaseCalls += 1
        return Result.success(ProEntitlement.Free)
    }

    override suspend fun restorePurchases(): Result<ProEntitlement> {
        restoreCalls += 1
        return Result.success(ProEntitlement.Free)
    }

    override fun close() = Unit
}

private class NoOpEntitlementCache : EntitlementCache {
    override suspend fun read(): ProEntitlement? = null

    override suspend fun write(entitlement: ProEntitlement) = Unit

    override suspend fun isReviewerUnlocked(): Boolean = false

    override suspend fun setReviewerUnlocked(unlocked: Boolean) = Unit

    override suspend fun clear() = Unit
}

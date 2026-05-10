// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pro

import com.kofikodr.kofipod.crypto.constantTimeHexEquals
import com.kofikodr.kofipod.crypto.sha256Hex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behaviour-level coverage for the reviewer-unlock affordance.
 *
 * The Critical invariant is in [`billing Free does not clobber a reviewer-unlocked cache`]:
 * after a reviewer enters the unlock code, a subsequent Play Billing query that
 * (correctly) reports "no purchase" must NOT downgrade the device to Free.
 * Without that overlay, Pro would visibly disappear on every cold start because
 * `refreshOnStart` runs a real billing query that returns Free for the reviewer.
 */
class ReviewerUnlockTest {
    // The hash baked in for these tests. Plaintext is "test-reviewer-code".
    // Computed via: printf 'test-reviewer-code' | shasum -a 256
    private val testCode = "test-reviewer-code"
    private val testHash = "0fbb58be339dc0c7bf2c7ddca3ea6d07e884fa5a16e6c54a441fc556bd918470"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `sha256Hex of test code matches expected hash`() {
        // Sanity-check the test fixture itself: if this fails, the rest of the
        // suite is meaningless because the "right" code wouldn't validate.
        assertTrue(constantTimeHexEquals(sha256Hex(testCode), testHash))
    }

    @Test
    fun `wrong code is rejected and state is unchanged`() =
        runTest {
            val cache = FakeEntitlementCache(initial = ProEntitlement.Free)
            val port = FakeBillingClientPort()
            val repo =
                ProEntitlementRepository(
                    cache = cache,
                    port = port,
                    appScope = scope,
                    reviewerUnlockHash = testHash,
                )
            repo.hydrateFromCache()
            val before = repo.state.value

            val ok = repo.applyReviewerUnlock("not-the-code")

            assertFalse(ok, "wrong code must not unlock")
            assertEquals(before, repo.state.value, "state must be unchanged on rejected unlock")
            assertFalse(cache.isReviewerUnlocked(), "cache flag must remain unset on rejected unlock")
        }

    @Test
    fun `correct code unlocks state and cache flag`() =
        runTest {
            val cache = FakeEntitlementCache(initial = ProEntitlement.Free)
            val port = FakeBillingClientPort()
            val repo =
                ProEntitlementRepository(
                    cache = cache,
                    port = port,
                    appScope = scope,
                    reviewerUnlockHash = testHash,
                )
            repo.hydrateFromCache()

            val ok = repo.applyReviewerUnlock(testCode)

            assertTrue(ok, "correct code must unlock")
            assertEquals(ProEntitlement.Pro(ProSource.ReviewerUnlock), repo.state.value)
            assertTrue(cache.isReviewerUnlocked(), "cache flag must be set after unlock")
        }

    @Test
    fun `empty hash disables unlock entirely`() =
        runTest {
            val cache = FakeEntitlementCache(initial = ProEntitlement.Free)
            val port = FakeBillingClientPort()
            val repo =
                ProEntitlementRepository(
                    cache = cache,
                    port = port,
                    appScope = scope,
                    reviewerUnlockHash = "",
                )

            // Even submitting the "correct" code that hashes to a real value must fail
            // when no expected hash is configured — release builds opting out of the
            // affordance must not have a backdoor.
            val ok = repo.applyReviewerUnlock(testCode)

            assertFalse(ok, "empty hash must reject every code")
            assertFalse(cache.isReviewerUnlocked())
        }

    @Test
    fun `billing Free does not clobber a reviewer-unlocked cache`() =
        runTest {
            // Reviewer-unlocked at start. Then billing returns Free (a real Play Billing
            // result for a reviewer who never bought the SKU). The state must remain Pro.
            val cache = FakeEntitlementCache(initial = ProEntitlement.Free, initialReviewerUnlock = true)
            val port = FakeBillingClientPort(query = Result.success(ProEntitlement.Free))
            val repo =
                ProEntitlementRepository(
                    cache = cache,
                    port = port,
                    appScope = scope,
                    reviewerUnlockHash = testHash,
                )

            repo.refreshOnStart()

            assertEquals(
                ProEntitlement.Pro(ProSource.ReviewerUnlock),
                repo.state.value,
                "billing Free must not downgrade a reviewer-unlocked device",
            )
            assertTrue(cache.isReviewerUnlocked(), "reviewer flag must survive billing refresh")
            // The underlying billing tier must also be persisted as the actual reading
            // (Free), so that if the reviewer flag is later revoked the device falls
            // back to the truth from billing rather than to a stale Pro reading.
            // Verify by clearing the overlay and re-reading: cache.read() returns the
            // billing tier directly when the flag is unset.
            cache.setReviewerUnlocked(false)
            assertEquals(
                ProEntitlement.Free,
                cache.read(),
                "billing-derived tier must be persisted as Free under the reviewer overlay",
            )
        }

    @Test
    fun `wrong code when already unlocked leaves state unchanged`() =
        runTest {
            // Critical-adjacent: a stray Settings-tap by an already-unlocked reviewer
            // who fat-fingers the code prompt must not revoke their unlock.
            val cache = FakeEntitlementCache(initial = ProEntitlement.Free, initialReviewerUnlock = true)
            val port = FakeBillingClientPort()
            val repo =
                ProEntitlementRepository(
                    cache = cache,
                    port = port,
                    appScope = scope,
                    reviewerUnlockHash = testHash,
                )
            repo.hydrateFromCache()

            val ok = repo.applyReviewerUnlock("wrong")

            assertFalse(ok)
            assertEquals(ProEntitlement.Pro(ProSource.ReviewerUnlock), repo.state.value)
            assertTrue(cache.isReviewerUnlocked(), "wrong code must not revoke an existing unlock")
        }

    @Test
    fun `whitespace-padded code is rejected`() =
        runTest {
            // The repository does NOT trim input before hashing — this test pins that
            // behaviour. The Settings UI trims the value before calling the repo, so
            // padding survives only as a forensic / regression signal here.
            val cache = FakeEntitlementCache(initial = ProEntitlement.Free)
            val port = FakeBillingClientPort()
            val repo =
                ProEntitlementRepository(
                    cache = cache,
                    port = port,
                    appScope = scope,
                    reviewerUnlockHash = testHash,
                )

            val padded = repo.applyReviewerUnlock(" $testCode ")

            assertFalse(padded, "padded input must hash to a different value and be rejected")
            assertFalse(cache.isReviewerUnlocked())
        }

    @Test
    fun `cache contract — clear wipes both tier and reviewer flag`() =
        runTest {
            // Contract test against EntitlementCache (via the fake): clear() must wipe
            // both the billing tier and the reviewer flag. ProEntitlementRepository has
            // no public clear() method, so this is the only place the contract is pinned.
            val cache =
                FakeEntitlementCache(
                    initial = ProEntitlement.Pro(ProSource.Individual),
                    initialReviewerUnlock = true,
                )

            cache.clear()

            assertFalse(cache.isReviewerUnlocked(), "clear must wipe reviewer flag")
            assertEquals(null, cache.read(), "clear must wipe tier")
        }

    @Test
    fun `cache contract — revoking reviewer flag restores underlying billing tier`() =
        runTest {
            // Pinning the EntitlementCache.read() contract: when the reviewer flag is
            // toggled off, read() must surface the underlying billing tier rather than
            // continuing to overlay Pro(ReviewerUnlock).
            val cache = FakeEntitlementCache(initial = ProEntitlement.Free, initialReviewerUnlock = true)
            assertEquals(ProEntitlement.Pro(ProSource.ReviewerUnlock), cache.read())

            cache.setReviewerUnlocked(false)

            assertEquals(ProEntitlement.Free, cache.read())
        }

    @Test
    fun `hydrateFromCache restores reviewer-unlocked state on cold start`() =
        runTest {
            // Simulates the cold-start path: the device has the reviewer flag set from
            // a previous session. Even before any billing query, hydrate should emit Pro.
            val cache = FakeEntitlementCache(initial = ProEntitlement.Free, initialReviewerUnlock = true)
            val port = FakeBillingClientPort()
            val repo =
                ProEntitlementRepository(
                    cache = cache,
                    port = port,
                    appScope = scope,
                    reviewerUnlockHash = testHash,
                )

            repo.hydrateFromCache()

            assertEquals(ProEntitlement.Pro(ProSource.ReviewerUnlock), repo.state.value)
        }
}

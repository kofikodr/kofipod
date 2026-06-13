// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pro

import com.kofikodr.kofipod.BuildConfig
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ReviewerUnlockConfigTest {
    @Test
    fun fossBuild_doesNotExposeReviewerUnlockHash_andRemainsUnconditionallyPro() =
        runTest {
            val cache = FakeCache(stored = ProEntitlement.Free)
            val repo =
                ProEntitlementRepository(
                    cache = cache,
                    port = FossBillingClientPort(),
                    appScope = backgroundScope,
                )

            repo.refreshOnStart()
            val rejected = repo.applyReviewerUnlock("test-reviewer-code")

            assertEquals("", BuildConfig.REVIEWER_UNLOCK_HASH)
            assertEquals("", ReviewerUnlockConfig.hash)
            assertFalse(rejected)
            assertFalse(cache.isReviewerUnlocked(), "blank FOSS hash must not set the reviewer flag")
            assertEquals(ProEntitlement.Pro(ProSource.FossBuild), repo.state.value)
            assertEquals(ProEntitlement.Pro(ProSource.FossBuild), cache.read())
        }

    private class FakeCache(
        private var stored: ProEntitlement?,
        private var reviewerUnlock: Boolean = false,
    ) : EntitlementCache {
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
}

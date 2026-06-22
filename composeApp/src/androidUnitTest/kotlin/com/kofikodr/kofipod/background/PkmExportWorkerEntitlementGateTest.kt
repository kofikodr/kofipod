// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

import com.kofikodr.kofipod.pro.ProEntitlement
import com.kofikodr.kofipod.pro.ProSource
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the entitlement policy for the background PKM export drain (issue #24).
 *
 * `PkmExportWorker` is the one caller that reaches `PkmExportCoordinator.retry` without
 * going through the ViewModel's Pro gate, so it must re-check entitlement before draining
 * connection-bound exports. The decision is [entitlementAllowsExportDrain]; the worker
 * itself (a `CoroutineWorker` + `KoinComponent`) isn't unit-testable here, so the policy
 * is pinned directly.
 */
class PkmExportWorkerEntitlementGateTest {
    @Test
    fun proEntitlement_allowsDraining_forEveryProSource() {
        // A confirmed Pro user — purchased, FOSS-build unconditional unlock, or reviewer
        // unlock — may drain their queued exports.
        assertTrue(entitlementAllowsExportDrain(ProEntitlement.Pro(ProSource.Individual)))
        assertTrue(entitlementAllowsExportDrain(ProEntitlement.Pro(ProSource.FossBuild)))
        assertTrue(entitlementAllowsExportDrain(ProEntitlement.Pro(ProSource.ReviewerUnlock)))
    }

    @Test
    fun freeEntitlement_blocksDraining() {
        // The crux of issue #24: a user who queued an export while Pro and then lapsed to
        // Free (e.g. a refund) must NOT have the queued export completed by the worker.
        assertFalse(entitlementAllowsExportDrain(ProEntitlement.Free))
    }

    @Test
    fun unknownEntitlement_blocksDraining() {
        // Unknown is the state when the billing query failed or the device is offline.
        // It must be treated as not-Pro (withhold the export), matching the paywall's
        // "treat Unknown as Free" rule — guards against a naive `!= Free` gate that would
        // wrongly let an unverified user drain.
        assertFalse(entitlementAllowsExportDrain(ProEntitlement.Unknown))
    }
}

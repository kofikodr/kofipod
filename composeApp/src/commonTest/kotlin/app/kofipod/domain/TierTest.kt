// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TierTest {
    // ---- Cold-start (no previous tier) maps strictly to the enter-threshold ladder. ----

    @Test
    fun `no previous - zero minutes maps to Decaf`() {
        assertEquals(Tier.Decaf, Tier.computeTier(avgMinPerDay = 0.0, previous = null))
    }

    @Test
    fun `no previous - just under Drip threshold stays at Decaf`() {
        assertEquals(Tier.Decaf, Tier.computeTier(avgMinPerDay = 9.999, previous = null))
    }

    @Test
    fun `no previous - exactly at Drip threshold promotes to Drip`() {
        assertEquals(Tier.Drip, Tier.computeTier(avgMinPerDay = 10.0, previous = null))
    }

    @Test
    fun `no previous - between two thresholds picks the lower one`() {
        // PourOver = 25, Espresso = 45 → 30 stays in PourOver.
        assertEquals(Tier.PourOver, Tier.computeTier(avgMinPerDay = 30.0, previous = null))
    }

    @Test
    fun `no previous - well above top tier still maps to top`() {
        assertEquals(Tier.TripleShot, Tier.computeTier(avgMinPerDay = 999.0, previous = null))
    }

    // ---- Promotions are eager: any rise past an enter threshold takes effect immediately. ----

    @Test
    fun `promotion takes effect at the next tier's enter threshold`() {
        // User was Drip (≥10), now averaging 26 → PourOver (≥25).
        assertEquals(Tier.PourOver, Tier.computeTier(avgMinPerDay = 26.0, previous = Tier.Drip))
    }

    @Test
    fun `multi-tier jump on a single recompute promotes all the way`() {
        // User was Drip, now averaging 80 → Doppio (≥75).
        assertEquals(Tier.Doppio, Tier.computeTier(avgMinPerDay = 80.0, previous = Tier.Drip))
    }

    // ---- Hysteresis on drops: hold until avg falls past dropMinPerDay (10% under enter). ----

    @Test
    fun `hysteresis hold - just above drop threshold preserves previous tier`() {
        // PourOver enters at 25, drops below 22.5. avg = 23 holds at PourOver.
        assertEquals(Tier.PourOver, Tier.computeTier(avgMinPerDay = 23.0, previous = Tier.PourOver))
    }

    @Test
    fun `hysteresis hold - exactly at drop threshold preserves previous tier`() {
        // 22.5 == PourOver.dropMinPerDay → still PourOver (drop only when < threshold).
        assertEquals(Tier.PourOver, Tier.computeTier(avgMinPerDay = 22.5, previous = Tier.PourOver))
    }

    @Test
    fun `hysteresis fail - below drop threshold snaps to whatever the ladder allows`() {
        // 22.4 < 22.5 → byEnter falls to Drip (≥10). Snap.
        assertEquals(Tier.Drip, Tier.computeTier(avgMinPerDay = 22.4, previous = Tier.PourOver))
    }

    @Test
    fun `hysteresis fail - multi-tier drop in one recompute is honored`() {
        // Was ColdBrew (110, drop 99). Now 20 → byEnter is Drip (≥10), and 20 < 99
        // so we don't hold ColdBrew. Land on Drip, not stuck at ColdBrew.
        assertEquals(Tier.Drip, Tier.computeTier(avgMinPerDay = 20.0, previous = Tier.ColdBrew))
    }

    @Test
    fun `hysteresis - sitting just below previous enter but above drop holds tier`() {
        // Espresso enters at 45, drops below 40.5. avg = 41 holds at Espresso.
        assertEquals(Tier.Espresso, Tier.computeTier(avgMinPerDay = 41.0, previous = Tier.Espresso))
    }

    // ---- Decaf has a 0 floor — no hysteresis ever pulls below it. ----

    @Test
    fun `Decaf - zero minutes from Decaf stays Decaf`() {
        assertEquals(Tier.Decaf, Tier.computeTier(avgMinPerDay = 0.0, previous = Tier.Decaf))
    }

    @Test
    fun `Decaf - the dropMinPerDay is zero so any positive avg still qualifies`() {
        assertEquals(0.0, Tier.Decaf.dropMinPerDay)
        // And the behavioral consequence: a tiny positive avg below the Drip enter
        // threshold from a Decaf previous holds Decaf, not anything lower.
        assertEquals(Tier.Decaf, Tier.computeTier(avgMinPerDay = 0.00005, previous = Tier.Decaf))
    }

    // ---- Top tier — there is no next, but hysteresis still applies on drop. ----

    @Test
    fun `TripleShot held above its drop threshold`() {
        // 150 enter, 135 drop. 140 holds.
        assertEquals(Tier.TripleShot, Tier.computeTier(avgMinPerDay = 140.0, previous = Tier.TripleShot))
    }

    @Test
    fun `TripleShot held exactly at its drop threshold stays TripleShot`() {
        // 150 * 0.9 = 135.0 exactly. The hold check is `>= dropMinPerDay`, so 135.0 holds.
        // Pins the inclusive-boundary semantics for the top tier specifically.
        assertEquals(Tier.TripleShot, Tier.computeTier(avgMinPerDay = 135.0, previous = Tier.TripleShot))
    }

    @Test
    fun `TripleShot drops to ColdBrew when below its drop threshold`() {
        // 134.999 < 135.0 → byEnter is ColdBrew (≥110). Probes the immediate-below-boundary
        // case rather than just "well below."
        assertEquals(Tier.ColdBrew, Tier.computeTier(avgMinPerDay = 134.999, previous = Tier.TripleShot))
    }

    @Test
    fun `top tier has no next`() {
        assertNull(Tier.TripleShot.next)
    }

    // ---- Drop thresholds are exactly 90% of enter (except Decaf). ----

    @Test
    fun `drop thresholds are 90 percent of enter thresholds`() {
        for (tier in Tier.entries) {
            if (tier == Tier.Decaf) continue
            assertEquals(
                expected = tier.enterMinPerDay * Tier.DROP_FRACTION,
                actual = tier.dropMinPerDay,
                absoluteTolerance = 0.0001,
                message = "Drop threshold for ${tier.displayName} should be 90% of enter",
            )
        }
    }

    // ---- Ladder ordering invariants. ----

    @Test
    fun `enter thresholds are strictly monotonically increasing`() {
        for ((a, b) in Tier.entries.zipWithNext()) {
            assertTrue(
                actual = b.enterMinPerDay > a.enterMinPerDay,
                message =
                    "Tier ladder out of order: ${a.displayName}=${a.enterMinPerDay}, " +
                        "${b.displayName}=${b.enterMinPerDay}",
            )
        }
    }

    @Test
    fun `rank matches ordinal-plus-one`() {
        for (tier in Tier.entries) {
            assertEquals(tier.ordinal + 1, tier.rank, "Rank for ${tier.displayName}")
        }
    }

    // Ensures every non-Decaf tier promotes inclusively at its exact enter threshold.
    // Catches a regression where a single `>=` becomes `>` inside the byEnter scan.
    @Test
    fun `every non-Decaf tier promotes at its exact enter threshold`() {
        for (tier in Tier.entries) {
            if (tier == Tier.Decaf) continue
            assertEquals(
                expected = tier,
                actual = Tier.computeTier(avgMinPerDay = tier.enterMinPerDay, previous = null),
                message = "Tier ${tier.displayName} should be reached at avg=${tier.enterMinPerDay}",
            )
        }
    }
}

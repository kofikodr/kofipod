// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.domain

/**
 * Coffee-themed tier ladder driven by the user's 30-day rolling average minutes/day
 * of listening. Hysteresis: enter at [enterMinPerDay], drop only below
 * `enterMinPerDay * 0.9`. Index 0 is the implicit zero state — the first real tier
 * is [Tier.Decaf] at index 1.
 */
enum class Tier(
    val rank: Int,
    val displayName: String,
    val enterMinPerDay: Double,
) {
    Decaf(rank = 1, displayName = "Decaf", enterMinPerDay = 0.0001),
    Drip(rank = 2, displayName = "Drip", enterMinPerDay = 10.0),
    PourOver(rank = 3, displayName = "Pour Over", enterMinPerDay = 25.0),
    Espresso(rank = 4, displayName = "Espresso", enterMinPerDay = 45.0),
    Doppio(rank = 5, displayName = "Doppio", enterMinPerDay = 75.0),
    ColdBrew(rank = 6, displayName = "Cold Brew", enterMinPerDay = 110.0),
    TripleShot(rank = 7, displayName = "Triple Shot", enterMinPerDay = 150.0),
    ;

    /** Drop-out threshold: 10% below the enter threshold (no hysteresis on Decaf). */
    val dropMinPerDay: Double
        get() = if (this == Decaf) 0.0 else enterMinPerDay * DROP_FRACTION

    val next: Tier?
        get() = entries.getOrNull(ordinal + 1)

    companion object {
        const val DROP_FRACTION = 0.9
        const val LEVEL_GATE_DAYS = 7
        const val ROLLING_WINDOW_DAYS = 30
        const val STREAK_MIN_SECONDS_PER_DAY = 5L * 60L

        /**
         * Compute the next tier given the current 30-day rolling average minutes/day
         * and the user's previously-emitted tier (used to apply hysteresis on drops).
         *
         * Rules:
         *   - To advance to tier N, [avgMinPerDay] must reach `N.enterMinPerDay`.
         *   - To drop out of [previous] tier, [avgMinPerDay] must fall below
         *     `previous.dropMinPerDay`. While in that band, [previous] is preserved.
         */
        fun computeTier(
            avgMinPerDay: Double,
            previous: Tier?,
        ): Tier {
            // Highest tier the user qualifies for purely by the enter-threshold ladder.
            val byEnter = entries.lastOrNull { avgMinPerDay >= it.enterMinPerDay } ?: Decaf
            if (previous == null) return byEnter
            // If the user has progressed to a higher tier than they used to be in, accept.
            if (byEnter.rank > previous.rank) return byEnter
            // Held in [previous] until they fall below its drop threshold; then snap to
            // whatever the enter-ladder says (which will be lower than previous).
            return if (avgMinPerDay >= previous.dropMinPerDay) previous else byEnter
        }
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.background

import com.kofikodr.kofipod.data.repo.SettingsRepository

/**
 * Single home for the "daily-check setting + periodic [Scheduler] work" coupling.
 *
 * Per-podcast auto-download and new-episode notifications only ever fire from the
 * periodic episode check, but the global "Daily check for new episodes" toggle
 * cancels that work independently — leaving the per-podcast switches ON with
 * nothing behind them (confirmed on-device, 2026-07-05). The Settings and
 * Scheduler-Details toggles route through [setEnabled]; enabling a dependent
 * feature calls [reassertEnabled] so most-recent user intent wins.
 *
 * Scheduler access is a pair of function seams rather than [Scheduler] itself so
 * JVM unit tests don't need a platform WorkManager; production wires
 * `scheduler::enable` / `scheduler::disable`, which are idempotent
 * (`ExistingPeriodicWorkPolicy.UPDATE` / `cancelUniqueWork`).
 */
class DailyCheckCoordinator(
    private val settings: SettingsRepository,
    private val enableScheduler: () -> Unit,
    private val disableScheduler: () -> Unit,
) {
    /** User explicitly toggled the daily check: persist the choice and (un)schedule the work. */
    fun setEnabled(on: Boolean) {
        settings.setDailyCheckEnabled(on)
        if (on) enableScheduler() else disableScheduler()
    }

    /**
     * Re-arm the daily check because a feature that depends on it was just enabled.
     *
     * @return true when the global setting was off and got flipped back on — callers
     * surface that to the user (the flip changes behavior for every subscribed show).
     */
    fun reassertEnabled(): Boolean {
        val wasOff = !settings.dailyCheckEnabledNow()
        if (wasOff) settings.setDailyCheckEnabled(true)
        enableScheduler()
        return wasOff
    }
}

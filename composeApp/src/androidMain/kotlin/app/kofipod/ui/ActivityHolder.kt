// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui

import androidx.activity.ComponentActivity

/**
 * Registry for the current foreground Activity. Used by Android-only ports that need an
 * Activity reference (e.g. PlayBillingClientPort.launchPurchase) without threading one
 * through every layer of the app.
 *
 * Lifecycle:
 * - MainActivity.onResume → set(this)
 * - MainActivity.onPause  → set(null)
 *
 * Callers must null-check [current] — the user can navigate away mid-flow, in which case
 * the caller should fail with a "no foreground activity" error rather than crashing.
 */
class ActivityHolder {
    @Volatile
    var current: ComponentActivity? = null
        private set

    fun set(activity: ComponentActivity?) {
        current = activity
    }
}

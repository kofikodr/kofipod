// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.background

/**
 * Sister to [Scheduler] (the episode-check worker scheduler) but for the SAF backup
 * worker. Mirrors its shape: `enable()` registers a periodic 24h work item with
 * charging + unmetered constraints; `disable()` cancels it. Always-on by default —
 * the worker no-ops if no folder URI is set.
 *
 * iOS gets a no-op actual; the SAF backup feature isn't surfaced on iOS in v1.
 */
expect class BackupScheduler {
    fun enable()

    fun disable()
}

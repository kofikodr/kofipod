// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.update

/**
 * Platform-agnostic surface for "is there a newer release than what's installed?".
 *
 * Android implementation talks to the GitHub releases API and persists results via
 * [app.kofipod.data.repo.UpdateRepository]. iOS implementation is a no-op (we don't
 * sideload on iOS).
 */
expect class UpdateChecker {
    /**
     * Returns the new [UpdateInfo] if one was found, or `null` if up-to-date / network
     * failure / throttled. When [force] is `false`, the implementation may skip the
     * network call if a recent check already happened (24h throttle).
     */
    suspend fun check(force: Boolean = false): UpdateInfo?
}

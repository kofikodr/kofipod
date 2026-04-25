// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.update

/**
 * Constants that describe where to look for releases. Centralised here so the API,
 * the checker, and the "view release" CTA all read from the same source.
 */
internal object UpdateConfig {
    const val OWNER = "kofikodr"
    const val REPO = "kofipod"

    /** Throttle window for non-forced checks. */
    const val CHECK_INTERVAL_MS: Long = 24L * 60L * 60L * 1000L
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

/**
 * Lazy-initialized crash-reporting facade. Constructor does NOT initialize
 * the underlying SDK. The SDK is loaded and configured on first [enable].
 */
expect class CrashReporter {
    fun enable()
    fun disable()
    fun isEnabled(): Boolean
}

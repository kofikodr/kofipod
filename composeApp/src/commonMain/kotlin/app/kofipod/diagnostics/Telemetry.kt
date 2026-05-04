// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

/**
 * Lazy-initialized telemetry facade. Constructor does NOT initialize the
 * underlying SDK. The SDK is loaded and configured on first [enable].
 * [track] is a no-op when not enabled.
 */
expect class Telemetry {
    fun enable()
    fun disable()
    fun track(event: TelemetryEvent)
}

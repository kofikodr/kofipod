// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.diagnostics

/**
 * Lazy-initialized telemetry facade. Implementations should not initialize
 * the underlying SDK in their constructor — only on first [enable].
 * [track] is a no-op when not enabled.
 *
 * Modelled as an interface (rather than expect class) so unit tests can
 * substitute [NoOpTelemetry] without standing up a Context.
 */
interface Telemetry {
    fun enable()

    fun disable()

    fun track(event: TelemetryEvent)

    /**
     * Debug-only smoke test: ensures the SDK is initialized (idempotent),
     * fires a single named event directly to the underlying SDK bypassing
     * the [enable]/[disable] gate, and returns a one-line status string
     * suitable for a snackbar. Isolates "is the SDK reachable from this
     * process?" from "is our gating wired correctly?".
     */
    fun debugSmokeTest(eventName: String): String
}

object NoOpTelemetry : Telemetry {
    override fun enable() = Unit

    override fun disable() = Unit

    override fun track(event: TelemetryEvent) = Unit

    override fun debugSmokeTest(eventName: String): String = "no-op (Telemetry stubbed)"
}

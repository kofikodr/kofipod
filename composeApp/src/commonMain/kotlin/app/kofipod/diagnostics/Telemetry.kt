// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

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
}

object NoOpTelemetry : Telemetry {
    override fun enable() = Unit

    override fun disable() = Unit

    override fun track(event: TelemetryEvent) = Unit
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

actual class Telemetry {
    actual fun enable() = Unit
    actual fun disable() = Unit
    actual fun track(event: TelemetryEvent) = Unit
}

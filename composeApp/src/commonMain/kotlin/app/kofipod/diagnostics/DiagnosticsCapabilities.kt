// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import app.kofipod.config.BuildKonfig

/**
 * Build-time view on which diagnostic channels are even reachable in
 * this APK. Forks / F-Droid builds with no secrets configured will see
 * both flags false; the disclosure sheet, the Settings rows, and the
 * underlying SDK init paths all gate on these to avoid presenting UI
 * the user can't actually opt into.
 */
object DiagnosticsCapabilities {
    val crashReportingAvailable: Boolean = BuildKonfig.SENTRY_DSN.isNotBlank()
    val usageTelemetryAvailable: Boolean = BuildKonfig.APTABASE_APP_KEY.isNotBlank()
    val anyAvailable: Boolean get() = crashReportingAvailable || usageTelemetryAvailable
}

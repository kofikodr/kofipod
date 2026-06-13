// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.diagnostics

/**
 * Build-time view on which diagnostic channels are even reachable in
 * this APK. Forks / F-Droid builds with no secrets configured will see
 * both flags false; the disclosure sheet, the Settings rows, and the
 * underlying SDK init paths all gate on these to avoid presenting UI
 * the user can't actually opt into.
 */
object DiagnosticsCapabilities {
    val crashReportingAvailable: Boolean = DiagnosticsConfig.sentryDsn.isNotBlank()
    val usageTelemetryAvailable: Boolean = DiagnosticsConfig.aptabaseAppKey.isNotBlank()
    val anyAvailable: Boolean get() = crashReportingAvailable || usageTelemetryAvailable
}

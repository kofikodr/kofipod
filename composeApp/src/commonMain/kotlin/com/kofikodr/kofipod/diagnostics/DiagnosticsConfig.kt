// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.diagnostics

/**
 * Build-time diagnostics credentials. Android reads flavor-scoped AGP
 * BuildConfig so public FOSS builds can disable maintainer-owned diagnostics.
 */
expect object DiagnosticsConfig {
    val sentryDsn: String
    val aptabaseAppKey: String
}

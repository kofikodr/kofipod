// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.diagnostics

import com.kofikodr.kofipod.BuildConfig

actual object DiagnosticsConfig {
    actual val sentryDsn: String = BuildConfig.SENTRY_DSN
    actual val aptabaseAppKey: String = BuildConfig.APTABASE_APP_KEY
}

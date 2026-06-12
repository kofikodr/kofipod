// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.diagnostics

import com.kofikodr.kofipod.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DiagnosticsConfigTest {
    @Test
    fun fossBuild_doesNotExposeDiagnosticsSecrets() {
        assertEquals("", BuildConfig.SENTRY_DSN)
        assertEquals("", BuildConfig.APTABASE_APP_KEY)
        assertEquals("", DiagnosticsConfig.sentryDsn)
        assertEquals("", DiagnosticsConfig.aptabaseAppKey)
        assertFalse(DiagnosticsCapabilities.crashReportingAvailable)
        assertFalse(DiagnosticsCapabilities.usageTelemetryAvailable)
        assertFalse(DiagnosticsCapabilities.anyAvailable)
    }
}

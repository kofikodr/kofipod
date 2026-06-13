// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.diagnostics

import com.kofikodr.kofipod.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class DiagnosticsConfigTest {
    @Test
    fun playBuildReadsDiagnosticsSecretsFromFlavorBuildConfig() {
        assertEquals(BuildConfig.SENTRY_DSN, DiagnosticsConfig.sentryDsn)
        assertEquals(BuildConfig.APTABASE_APP_KEY, DiagnosticsConfig.aptabaseAppKey)
    }
}

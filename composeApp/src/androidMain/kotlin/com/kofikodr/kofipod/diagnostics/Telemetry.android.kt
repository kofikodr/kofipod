// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.diagnostics

import android.content.Context
import com.aptabase.Aptabase

/**
 * Android-side usage telemetry using Aptabase. Identifier-less by
 * construction — never passes a userId, never persists a per-install ID.
 * Aptabase's server hashes IP + UA + a daily-rotated salt so the same
 * device is a different ID every 24h.
 *
 * If the app key is empty (F-Droid build, fork without secrets), [enable]
 * is a permanent no-op.
 */
class AndroidTelemetry(private val context: Context) : Telemetry {
    private var enabled = false

    override fun enable() {
        if (enabled) return
        val key = DiagnosticsConfig.aptabaseAppKey
        if (key.isBlank()) return
        Aptabase.instance.initialize(context, key)
        enabled = true
    }

    override fun disable() {
        if (!enabled) return
        // Aptabase has no explicit close; gating future track() calls is enough.
        enabled = false
    }

    override fun track(event: TelemetryEvent) {
        if (!enabled) return
        Aptabase.instance.trackEvent(event.name, event.props)
    }

    override fun debugSmokeTest(eventName: String): String {
        val key = DiagnosticsConfig.aptabaseAppKey
        if (key.isBlank()) return "FAIL: APTABASE_APP_KEY blank in DiagnosticsConfig"
        return runCatching {
            Aptabase.instance.initialize(context, key)
            Aptabase.instance.trackEvent(eventName, emptyMap())
            "fired '$eventName' (gated.enabled=$enabled, key=${key.take(7)}…)"
        }.getOrElse { e -> "FAIL: ${e::class.simpleName}: ${e.message ?: "(no message)"}" }
    }
}

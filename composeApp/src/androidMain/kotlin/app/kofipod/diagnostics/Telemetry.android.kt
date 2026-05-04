// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import android.content.Context
import app.kofipod.config.BuildKonfig
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
        if (BuildKonfig.APTABASE_APP_KEY.isBlank()) return
        Aptabase.instance.initialize(context, BuildKonfig.APTABASE_APP_KEY)
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
}

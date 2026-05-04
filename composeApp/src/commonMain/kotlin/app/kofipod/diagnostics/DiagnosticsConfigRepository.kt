// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import kotlinx.coroutines.flow.Flow

/**
 * Three persistent boolean flags controlling diagnostics:
 *
 * - [crashesEnabled] — user-controlled toggle for crash reports
 * - [usageEnabled] — user-controlled toggle for usage events
 * - [disclosureAcknowledged] — set true when the user acknowledges the
 *   first-launch disclosure. Until true, [DiagnosticsBootstrapper] keeps
 *   both subsystems disabled regardless of the toggles.
 *
 * Default values: crashes ON, usage ON, acknowledged FALSE.
 */
interface DiagnosticsConfigRepository {
    val crashesEnabled: Flow<Boolean>
    val usageEnabled: Flow<Boolean>
    val disclosureAcknowledged: Flow<Boolean>

    suspend fun setCrashesEnabled(enabled: Boolean)

    suspend fun setUsageEnabled(enabled: Boolean)

    suspend fun acknowledgeDisclosure()
}

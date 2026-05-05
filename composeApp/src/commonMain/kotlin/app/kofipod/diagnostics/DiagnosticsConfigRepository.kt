// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

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

/**
 * iOS / fork / non-configured-build stub. Reports disclosure unacknowledged
 * forever, so [DiagnosticsBootstrapper.effective] never flips true and the
 * SDK facades stay no-op regardless of toggle state. Mutators are no-ops.
 * Exists so the iOS Koin graph can resolve [DiagnosticsConfigRepository]
 * with the same shape as Android — even though iOS has no `startKoin`
 * entry point today, keeping the graph parallel avoids a latent
 * NoBeanDefFoundException the moment iOS is wired up.
 */
object NoOpDiagnosticsConfigRepository : DiagnosticsConfigRepository {
    override val crashesEnabled: Flow<Boolean> = flowOf(false)
    override val usageEnabled: Flow<Boolean> = flowOf(false)
    override val disclosureAcknowledged: Flow<Boolean> = flowOf(false)

    override suspend fun setCrashesEnabled(enabled: Boolean) = Unit

    override suspend fun setUsageEnabled(enabled: Boolean) = Unit

    override suspend fun acknowledgeDisclosure() = Unit
}

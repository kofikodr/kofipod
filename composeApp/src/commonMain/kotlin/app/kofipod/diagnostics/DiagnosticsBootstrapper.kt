// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Wires [DiagnosticsConfigRepository] flags to the two SDK facades.
 *
 * Effective state for each subsystem is `toggle && disclosureAcknowledged`.
 * Until the user has acknowledged the first-launch disclosure, neither
 * SDK is initialized regardless of toggle state.
 */
class DiagnosticsBootstrapper(
    private val config: DiagnosticsConfigRepository,
    private val crashes: CrashReporter,
    private val telemetry: Telemetry,
    private val appScope: CoroutineScope,
) {
    fun start() {
        effective(config.crashesEnabled, config.disclosureAcknowledged)
            .onEach { if (it) crashes.enable() else crashes.disable() }
            .launchIn(appScope)
        effective(config.usageEnabled, config.disclosureAcknowledged)
            .onEach { if (it) telemetry.enable() else telemetry.disable() }
            .launchIn(appScope)
    }

    companion object {
        fun effective(toggle: Flow<Boolean>, acknowledged: Flow<Boolean>): Flow<Boolean> =
            combine(toggle, acknowledged) { t, a -> t && a }.distinctUntilChanged()
    }
}

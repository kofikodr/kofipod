// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.diagnostics

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val _telemetryReady = MutableStateFlow(false)

    /**
     * Mirrors the effective telemetry-enabled state, but only flips after
     * [Telemetry.enable]/[Telemetry.disable] has actually run. Subscribers
     * (e.g. the AppOpened firing in [com.kofikodr.kofipod.KofipodApplication]) can
     * await `first { it }` to know the SDK is fully initialized — this
     * eliminates the cold-start race where the first track() call fired
     * before the bootstrapper had a chance to enable the SDK.
     */
    val telemetryReady: StateFlow<Boolean> = _telemetryReady.asStateFlow()

    fun start() {
        effective(config.crashesEnabled, config.disclosureAcknowledged)
            .onEach { effective ->
                runCatching { if (effective) crashes.enable() else crashes.disable() }
            }
            .launchIn(appScope)
        effective(config.usageEnabled, config.disclosureAcknowledged)
            .onEach { effective ->
                // Wrap SDK calls in runCatching so a throwing initialize() never
                // tears down the collector — if it did, telemetryReady would
                // stay false forever and any awaiter (e.g. AppOpened in
                // KofipodApplication) would suspend for the process lifetime.
                // We still flip telemetryReady so awaiters unblock; the
                // !enabled guard inside Telemetry.track() handles a failed
                // init gracefully (track becomes a no-op).
                runCatching { if (effective) telemetry.enable() else telemetry.disable() }
                _telemetryReady.value = effective
            }
            .launchIn(appScope)
    }

    companion object {
        fun effective(
            toggle: Flow<Boolean>,
            acknowledged: Flow<Boolean>,
        ): Flow<Boolean> = combine(toggle, acknowledged) { t, a -> t && a }.distinctUntilChanged()
    }
}

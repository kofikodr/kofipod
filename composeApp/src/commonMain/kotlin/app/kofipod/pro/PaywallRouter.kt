// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single-call entry point for showing the Paywall sheet from anywhere in the UI.
 *
 * State machine: [PaywallState.Hidden] <-> [PaywallState.Visible]. The trigger key is opaque
 * to the router but lets the Paywall ViewModel record locally which surface caused the
 * conversion (e.g. `paywall_bookmark`, `paywall_snip`, `paywall_settings`) for the developer's
 * debug-build inspection only — never transmitted.
 */
class PaywallRouter {
    private val _state = MutableStateFlow<PaywallState>(PaywallState.Hidden)
    val state: StateFlow<PaywallState> = _state.asStateFlow()

    fun requestPaywall(triggerKey: String) {
        _state.value = PaywallState.Visible(triggerKey)
    }

    fun dismiss() {
        _state.value = PaywallState.Hidden
    }
}

sealed class PaywallState {
    data object Hidden : PaywallState()

    data class Visible(val triggerKey: String) : PaywallState()
}

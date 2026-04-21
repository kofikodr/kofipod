// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.network

import kotlinx.coroutines.flow.StateFlow

enum class NetworkType { Wifi, Metered, None }

/**
 * Reactive view of the device's current connectivity. The repository layer uses this
 * to gate downloads that should only run on Wi-Fi. An interface rather than an
 * `expect class` so tests can provide a fake without a real OS `ConnectivityManager`.
 */
interface NetworkMonitor {
    val type: StateFlow<NetworkType>
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.network

import kotlinx.coroutines.flow.StateFlow

enum class NetworkType { Wifi, Metered, None }

expect class NetworkMonitor {
    val type: StateFlow<NetworkType>
}

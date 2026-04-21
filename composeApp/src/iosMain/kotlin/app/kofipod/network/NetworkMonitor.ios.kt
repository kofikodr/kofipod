// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class IosNetworkMonitor : NetworkMonitor {
    // TODO NWPathMonitor — iOS is secondary; treat as Wi-Fi so downloads aren't blocked.
    private val _type = MutableStateFlow(NetworkType.Wifi)
    override val type: StateFlow<NetworkType> = _type.asStateFlow()
}

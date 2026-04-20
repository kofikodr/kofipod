// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

actual class NetworkMonitor {
    // TODO NWPathMonitor — iOS is secondary; treat as Wi-Fi so playback behavior matches "no limits".
    private val _type = MutableStateFlow(NetworkType.Wifi)
    actual val type: StateFlow<NetworkType> = _type.asStateFlow()
}

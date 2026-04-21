// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val _type = MutableStateFlow(resolveCurrent())
    override val type: StateFlow<NetworkType> = _type.asStateFlow()

    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = refresh()

            override fun onLost(network: Network) = refresh()

            override fun onCapabilitiesChanged(
                network: Network,
                caps: NetworkCapabilities,
            ) = refresh()
        }

    init {
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            callback,
        )
    }

    /** Tear down the OS callback. Process-singleton in practice; provided for test cleanup. */
    fun release() {
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun refresh() {
        _type.value = resolveCurrent()
    }

    private fun resolveCurrent(): NetworkType {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NetworkType.None
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return NetworkType.None
        // Trust the metered flag only — aligns with the Wi-Fi-only download gate. A Wi-Fi hotspot
        // the user has flagged as metered (or a cellular tether on Wi-Fi transport) correctly
        // falls into the "metered" bucket and is blocked when the setting is on.
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {
            NetworkType.Wifi
        } else {
            NetworkType.Metered
        }
    }
}

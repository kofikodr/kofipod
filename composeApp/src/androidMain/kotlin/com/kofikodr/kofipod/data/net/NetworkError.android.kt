// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.net

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

internal actual fun Throwable.isPlatformConnectivityError(): Boolean = walkCauses { it.matchesConnectivity() }

internal actual fun Throwable.isPlatformTimeout(): Boolean = walkCauses { it is SocketTimeoutException }

private fun Throwable.matchesConnectivity(): Boolean =
    this is UnknownHostException ||
        this is ConnectException ||
        this is NoRouteToHostException

private inline fun Throwable.walkCauses(predicate: (Throwable) -> Boolean): Boolean {
    var current: Throwable? = this
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        if (predicate(current)) return true
        current = current.cause
    }
    return false
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.net

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException

sealed interface NetworkError {
    data object Offline : NetworkError

    data object Timeout : NetworkError

    data class Other(val original: Throwable) : NetworkError

    companion object {
        fun classify(throwable: Throwable): NetworkError =
            when {
                throwable.anyCauseIsKtorTimeout() -> Timeout
                throwable.isPlatformTimeout() -> Timeout
                throwable.isPlatformConnectivityError() -> Offline
                else -> Other(throwable)
            }

        fun toUserMessage(
            throwable: Throwable,
            fallback: String = DEFAULT_FALLBACK,
        ): String =
            when (val classified = classify(throwable)) {
                Offline -> OFFLINE_MESSAGE
                Timeout -> TIMEOUT_MESSAGE
                is Other -> classified.original.message?.takeIf { it.isNotBlank() } ?: fallback
            }

        const val OFFLINE_MESSAGE = "You're offline. Check your connection and try again."
        const val TIMEOUT_MESSAGE = "The request took too long. Try again in a moment."
        const val DEFAULT_FALLBACK = "Something went wrong"
    }
}

private fun Throwable.anyCauseIsKtorTimeout(): Boolean {
    // Walks the cause chain so SDKs that wrap Ktor exceptions (e.g. PodcastIndexClient
    // surfacing a generic IOException with a Ktor timeout as cause) are still classified
    // correctly. Identity-set guards against cyclic chains.
    var current: Throwable? = this
    val seen = mutableSetOf<Throwable>()
    while (current != null && seen.add(current)) {
        if (current is HttpRequestTimeoutException ||
            current is ConnectTimeoutException ||
            current is SocketTimeoutException
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

internal expect fun Throwable.isPlatformConnectivityError(): Boolean

internal expect fun Throwable.isPlatformTimeout(): Boolean

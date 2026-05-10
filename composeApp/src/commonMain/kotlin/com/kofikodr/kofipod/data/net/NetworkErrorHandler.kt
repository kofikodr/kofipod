// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.net

import com.kofikodr.kofipod.ui.UiEvent
import com.kofikodr.kofipod.ui.UiEventBus
import kotlinx.coroutines.CancellationException

/**
 * Single entry point for ViewModels handling errors from network calls.
 *
 * Convention for new screens:
 * 1. Inject `NetworkErrorHandler` via Koin.
 * 2. In every `runCatching { repo.fetch() }.onFailure { e -> ... }` block call
 *    `errors.handle(e, hasCachedData = state.value.cached.isNotEmpty())`.
 *    If `hasCachedData` is true and the error is a connectivity/timeout error,
 *    a transient snackbar is emitted and `null` is returned (suppress empty state).
 *    Otherwise a friendly user-facing message is returned for the screen's `error` field.
 * 3. Re-throw `CancellationException` outside of this helper as needed.
 */
class NetworkErrorHandler(private val bus: UiEventBus) {
    fun handle(
        throwable: Throwable,
        hasCachedData: Boolean = false,
        fallback: String = NetworkError.DEFAULT_FALLBACK,
    ): String? {
        if (throwable is CancellationException) throw throwable
        val classified = NetworkError.classify(throwable)
        return when (classified) {
            NetworkError.Offline -> handleTransientOrFriendly(NetworkError.OFFLINE_MESSAGE, hasCachedData)
            NetworkError.Timeout -> handleTransientOrFriendly(NetworkError.TIMEOUT_MESSAGE, hasCachedData)
            is NetworkError.Other -> throwable.message?.takeIf { it.isNotBlank() } ?: fallback
        }
    }

    private fun handleTransientOrFriendly(
        message: String,
        hasCachedData: Boolean,
    ): String? {
        if (hasCachedData) {
            bus.emit(UiEvent.Snackbar(message))
            return null
        }
        return message
    }
}

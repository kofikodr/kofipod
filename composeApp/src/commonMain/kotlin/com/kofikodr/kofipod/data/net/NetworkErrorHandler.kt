// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.net

import kotlinx.coroutines.CancellationException

/**
 * Single entry point for ViewModels handling errors from network calls.
 *
 * Pure data-layer concern: classifies the throwable into a user-facing
 * message and decides whether a transient (cache-protected) error should
 * be surfaced as an inline error or hidden behind a snackbar.
 *
 * Snackbar emission is the caller's responsibility — pass [emitSnackbar]
 * to wire it through your own [com.kofikodr.kofipod.ui.UiEventBus]
 * instance. The handler itself has no dependency on the UI layer so it
 * can live in `data/net/` without violating the data → ui boundary.
 *
 * Convention for new screens:
 * 1. Inject `NetworkErrorHandler` via Koin.
 * 2. In every `runCatching { repo.fetch() }.onFailure { e -> ... }` block call
 *    `errors.handle(e, hasCachedData = state.value.cached.isNotEmpty(),
 *    emitSnackbar = { bus.emit(UiEvent.Snackbar(it)) })`.
 *    If `hasCachedData` is true and the error is a connectivity/timeout error,
 *    the snackbar callback fires (if provided) and `null` is returned so
 *    the screen suppresses its empty-state.
 *    Otherwise a friendly user-facing message is returned for the screen's
 *    `error` field.
 * 3. Re-throw `CancellationException` outside of this helper as needed.
 */
class NetworkErrorHandler {
    fun handle(
        throwable: Throwable,
        hasCachedData: Boolean = false,
        fallback: String = NetworkError.DEFAULT_FALLBACK,
        emitSnackbar: ((String) -> Unit)? = null,
    ): String? {
        if (throwable is CancellationException) throw throwable
        val classified = NetworkError.classify(throwable)
        return when (classified) {
            NetworkError.Offline -> handleTransientOrFriendly(NetworkError.OFFLINE_MESSAGE, hasCachedData, emitSnackbar)
            NetworkError.Timeout -> handleTransientOrFriendly(NetworkError.TIMEOUT_MESSAGE, hasCachedData, emitSnackbar)
            is NetworkError.Other -> throwable.message?.takeIf { it.isNotBlank() } ?: fallback
        }
    }

    private fun handleTransientOrFriendly(
        message: String,
        hasCachedData: Boolean,
        emitSnackbar: ((String) -> Unit)?,
    ): String? {
        if (hasCachedData) {
            emitSnackbar?.invoke(message)
            return null
        }
        return message
    }
}

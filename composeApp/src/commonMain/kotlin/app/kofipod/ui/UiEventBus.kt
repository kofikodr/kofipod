// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface UiEvent {
    data class Snackbar(val message: String) : UiEvent
}

class UiEventBus {
    // extraBufferCapacity gives `tryEmit` non-suspending semantics so VM call sites stay
    // synchronous; replay = 0 means a snackbar shown after navigation away is forgotten,
    // which matches user expectation for transient toasts.
    private val _events =
        MutableSharedFlow<UiEvent>(
            replay = 0,
            extraBufferCapacity = EXTRA_BUFFER_CAPACITY,
        )
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    fun emit(event: UiEvent) {
        _events.tryEmit(event)
    }

    private companion object {
        const val EXTRA_BUFFER_CAPACITY = 8
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-singleton state bridge between the Android render service and the
 * common-code editor. Service writes via [publish]; the [SnippetRenderLauncher]
 * exposes the read side as [state]. iOS code never publishes — Snippets is
 * Android-only — but the bus itself is pure Kotlin so iOS compile stays green.
 */
object SnippetRenderProgressBus {
    private val _state = MutableStateFlow<RenderProgress>(RenderProgress.Idle)
    val state: StateFlow<RenderProgress> = _state.asStateFlow()

    fun publish(progress: RenderProgress) {
        _state.value = progress
    }
}

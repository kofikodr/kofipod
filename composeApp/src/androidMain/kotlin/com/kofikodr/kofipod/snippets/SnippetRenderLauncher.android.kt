// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

actual class SnippetRenderLauncher(private val context: Context) {
    actual fun enqueue(snippetId: String) {
        // Publish optimistic InFlight so the editor flips to "Rendering…" at once.
        // The render service will overwrite this with real fraction/Complete/Failed
        // as it makes progress; this initial publish makes the editor's UI flip
        // immediately instead of after the service's first poll tick.
        SnippetRenderProgressBus.publish(RenderProgress.InFlight(snippetId, fraction = 0f))
        try {
            SnippetRenderBroadcaster.enqueue(context, snippetId)
        } catch (t: Throwable) {
            // Foreground-service start can throw on Android 12+ background
            // restrictions or when the FOREGROUND_SERVICE permission is denied.
            // Publish Failed so the editor's UI returns to a terminal state
            // instead of staying stuck at "Rendering…" 0%.
            SnippetRenderProgressBus.publish(
                RenderProgress.Failed(snippetId, t.message ?: "Service start failed"),
            )
        }
    }

    actual fun cancel(snippetId: String) {
        // Local Idle publish first so the UI returns to a terminal state
        // immediately, even if the service's onStartCommand is delayed by
        // a few ms. The service will also publish Idle when it processes the
        // ACTION_CANCEL — that's a safe duplicate.
        SnippetRenderProgressBus.publish(RenderProgress.Idle)
        runCatching { SnippetRenderBroadcaster.cancel(context, snippetId) }
    }

    actual val progress: StateFlow<RenderProgress> = SnippetRenderProgressBus.state
}

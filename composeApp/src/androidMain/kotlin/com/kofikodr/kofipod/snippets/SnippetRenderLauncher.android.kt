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

    actual val progress: StateFlow<RenderProgress> = SnippetRenderProgressBus.state
}

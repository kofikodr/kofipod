// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

actual class SnippetRenderLauncher(private val context: Context) {
    actual fun enqueue(snippetId: String) {
        // Publish optimistic InFlight before kicking off the foreground service.
        // The render service will update the bus with real fraction/Complete/Failed
        // as it makes progress; this initial publish makes the editor's UI flip
        // to "Rendering…" immediately instead of after the service's first poll tick.
        SnippetRenderProgressBus.publish(RenderProgress.InFlight(snippetId, fraction = 0f))
        SnippetRenderBroadcaster.enqueue(context, snippetId)
    }

    actual val progress: StateFlow<RenderProgress> = SnippetRenderProgressBus.state
}

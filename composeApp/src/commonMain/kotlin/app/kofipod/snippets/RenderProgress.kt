// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Observable render lifecycle. The editor subscribes via
 * [SnippetRenderLauncher.progress] and shows the design's rendering / complete
 * / error states inline instead of returning to Player on enqueue.
 */
sealed interface RenderProgress {
    data object Idle : RenderProgress

    data class InFlight(val snippetId: String, val fraction: Float) : RenderProgress

    data class Complete(val snippetId: String, val path: String, val format: SnippetFormat) : RenderProgress

    data class Failed(val snippetId: String, val message: String) : RenderProgress
}

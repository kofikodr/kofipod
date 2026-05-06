// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import kotlinx.coroutines.flow.StateFlow

/**
 * Common-side handle that hands a snippetId off to the platform's render
 * pipeline. Android: starts SnippetRenderService as a foreground service.
 * iOS: no-op (Snippets are Android-only this milestone).
 *
 * [progress] is exposed here as a convenience pass-through over
 * [SnippetRenderProgressBus] so the editor depends only on the launcher.
 */
expect class SnippetRenderLauncher {
    fun enqueue(snippetId: String)

    val progress: StateFlow<RenderProgress>
}

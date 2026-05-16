// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

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

    /**
     * Cancel the in-flight render for [snippetId]. Best-effort: if the render
     * has already completed (Transformer's onCompleted fired) the share intent
     * may still trigger; otherwise the service cancels the encode job and
     * stops itself. The bus is reset to [RenderProgress.Idle] so a late
     * Complete from a racing finalisation can't pop the share dialog.
     */
    fun cancel(snippetId: String)

    val progress: StateFlow<RenderProgress>
}

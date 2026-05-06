// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Common-side handle that hands a snippetId off to the platform's render
 * pipeline. Android: starts SnippetRenderService as a foreground service.
 * iOS: no-op (Snippets are Android-only this milestone).
 */
expect class SnippetRenderLauncher {
    fun enqueue(snippetId: String)
}

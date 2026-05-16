// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Thin process-wide entry point used to dispatch a render request to the
 * Android foreground service. Mirrors the shape of [com.kofikodr.kofipod.downloads.DownloadBroadcaster]
 * — keeps the `startForegroundService` plumbing in one place so the platform
 * launcher (and any future internal caller) can stay declarative.
 */
object SnippetRenderBroadcaster {
    fun enqueue(
        context: Context,
        snippetId: String,
    ) {
        val intent =
            Intent(context, SnippetRenderService::class.java).apply {
                putExtra(SnippetRenderService.EXTRA_SNIPPET_ID, snippetId)
            }
        ContextCompat.startForegroundService(context, intent)
    }

    /**
     * Tell the render service to cancel the in-flight render for [snippetId].
     * Implemented as a fresh foreground-service start with an ACTION_CANCEL
     * extra so we don't have to bind the service for a one-shot signal.
     */
    fun cancel(
        context: Context,
        snippetId: String,
    ) {
        val intent =
            Intent(context, SnippetRenderService::class.java).apply {
                action = SnippetRenderService.ACTION_CANCEL
                putExtra(SnippetRenderService.EXTRA_SNIPPET_ID, snippetId)
            }
        ContextCompat.startForegroundService(context, intent)
    }
}

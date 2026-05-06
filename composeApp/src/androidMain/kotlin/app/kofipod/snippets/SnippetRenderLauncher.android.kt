// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

import android.content.Context

actual class SnippetRenderLauncher(private val context: Context) {
    actual fun enqueue(snippetId: String) =
        SnippetRenderBroadcaster.enqueue(context, snippetId)
}

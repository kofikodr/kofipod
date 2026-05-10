// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.snippets

import kotlinx.coroutines.flow.StateFlow

actual class SnippetRenderLauncher {
    actual fun enqueue(snippetId: String) {
        // Snippets are Android-only this milestone.
    }

    actual val progress: StateFlow<RenderProgress> = SnippetRenderProgressBus.state
}

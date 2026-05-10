// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.connections

import androidx.compose.runtime.Composable

@Composable
actual fun rememberObsidianFolderPicker(onPicked: (treeUri: String) -> Unit): () -> Unit =
    { /* no-op: Obsidian SAF not available on iOS */ }

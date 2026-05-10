// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.connections

import androidx.compose.runtime.Composable

/**
 * Returns a callback that, when invoked, opens a system folder picker and
 * delivers the selected tree URI string to [onPicked]. On iOS this is a
 * no-op — Obsidian sync on iOS is deferred to a later slice.
 */
@Composable
expect fun rememberObsidianFolderPicker(onPicked: (treeUri: String) -> Unit): () -> Unit

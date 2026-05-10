// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.sinks

/** Writes a single Markdown blob into a SAF tree URI (Obsidian vault). */
interface ObsidianFolderWriter {
    suspend fun write(
        treeUri: String,
        filename: String,
        body: String,
    )
}

/** Platform-backed writer. Android wraps SAF DocumentFile; iOS throws (v1.0 Android-only). */
expect class ObsidianFolderWriterImpl : ObsidianFolderWriter {
    override suspend fun write(
        treeUri: String,
        filename: String,
        body: String,
    )
}

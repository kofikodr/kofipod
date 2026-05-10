// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.sinks

actual class ObsidianFolderWriterImpl : ObsidianFolderWriter {
    actual override suspend fun write(
        treeUri: String,
        filename: String,
        body: String,
    ) {
        throw NotImplementedError("Obsidian on iOS is not supported in v1.0")
    }
}

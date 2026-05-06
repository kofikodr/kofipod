// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.sinks

actual open class ObsidianFolderWriter {
    actual open suspend fun write(
        treeUri: String,
        filename: String,
        body: String,
    ) {
        throw NotImplementedError("Obsidian on iOS is not supported in v1.0")
    }
}

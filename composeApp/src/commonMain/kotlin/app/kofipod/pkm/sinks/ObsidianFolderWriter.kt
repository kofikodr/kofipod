// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.sinks

expect open class ObsidianFolderWriter() {
    open suspend fun write(
        treeUri: String,
        filename: String,
        body: String,
    )
}

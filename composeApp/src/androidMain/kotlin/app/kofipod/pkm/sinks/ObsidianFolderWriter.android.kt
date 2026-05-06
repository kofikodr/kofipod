// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.sinks

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual open class ObsidianFolderWriter {
    actual open suspend fun write(
        treeUri: String,
        filename: String,
        body: String,
    ) {
        withContext(Dispatchers.IO) {
            val contextObj =
                contextProvider()
                    ?: error("Context not provided to ObsidianFolderWriter")
            val context = contextObj as Context
            val tree =
                DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                    ?: error("Cannot resolve Obsidian folder; permission may have been revoked")
            tree.findFile(filename)?.delete()
            val file =
                tree.createFile("text/markdown", filename)
                    ?: error("Could not create file $filename in Obsidian folder")
            context.contentResolver.openOutputStream(file.uri)?.use { stream ->
                stream.write(body.toByteArray(Charsets.UTF_8))
            } ?: error("Could not open output stream for $filename")
        }
    }

    companion object {
        var contextProvider: () -> Any? = { null }
    }
}

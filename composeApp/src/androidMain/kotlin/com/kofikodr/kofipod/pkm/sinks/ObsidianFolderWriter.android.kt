// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.sinks

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual class ObsidianFolderWriterImpl(private val context: Context) : ObsidianFolderWriter {
    /**
     * Writes [body] to `<treeUri>/<filename>` via the SAF DocumentFile API.
     * Uses a write-then-replace pattern so the existing note survives any
     * failure mode (revoked SAF permission, quota exhausted, IO error,
     * device sleep mid-write):
     *
     *   1. Write the full payload to `<filename>.tmp` first.
     *   2. Only after the temp file is fully written + closed, delete the
     *      existing file (if any).
     *   3. Rename the temp to the target filename.
     *
     * The previous implementation deleted the existing file BEFORE creating
     * the replacement, so any failure between delete and successful write
     * destroyed the user's note with nothing to recover.
     */
    actual override suspend fun write(
        treeUri: String,
        filename: String,
        body: String,
    ) {
        withContext(Dispatchers.IO) {
            val tree =
                DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                    ?: error("Cannot resolve Obsidian folder; permission may have been revoked")

            val tmpName = "$filename.kofipod-tmp"
            // Defensive: a prior aborted write may have left a stale tmp file
            // around. Remove it before we start so createFile doesn't get a
            // collision-suffixed name like "$filename.kofipod-tmp (1)".
            tree.findFile(tmpName)?.delete()

            val tmpFile =
                tree.createFile("text/markdown", tmpName)
                    ?: error("Could not create temp file $tmpName in Obsidian folder")

            var tmpWritten = false
            try {
                context.contentResolver.openOutputStream(tmpFile.uri)?.use { stream ->
                    stream.write(body.toByteArray(Charsets.UTF_8))
                } ?: error("Could not open output stream for $tmpName")
                tmpWritten = true
            } finally {
                if (!tmpWritten) {
                    // Write failed mid-flight; clean up the partial temp so it
                    // doesn't accumulate. The original note (if any) is still
                    // intact because we haven't touched it yet.
                    runCatching { tmpFile.delete() }
                }
            }

            // Temp is fully written. Now we can safely replace the original:
            // delete-then-rename. If the rename ever fails, the user is left
            // with the .kofipod-tmp file rather than no file at all — the
            // next sync will overwrite it via the tmp-prefix cleanup above.
            // Surface a rename failure to the caller so the sync isn't
            // silently considered successful.
            tree.findFile(filename)?.delete()
            if (!tmpFile.renameTo(filename)) {
                error(
                    "Could not rename $tmpName to $filename in Obsidian folder; " +
                        "next sync will overwrite the leftover .kofipod-tmp file.",
                )
            }
        }
    }
}

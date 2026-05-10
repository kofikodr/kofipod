// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Bridges suspending [BackupFilePort] calls (made from common code) to the Activity-scoped
 * SAF launchers (which are Compose-rooted) plus the SAF write surface (which is Activity-
 * independent — it uses the persisted tree URI).
 *
 * Two flows back the picker calls; the host composable consumes them and resolves the
 * deferred. Buffered with `extraBufferCapacity = 1` so a request queued before the host
 * has subscribed isn't lost; back-to-back requests still suspend the second caller until
 * the first completes (single-flight is enforced by [BackupController], not here).
 *
 * The 5-minute [PICKER_TIMEOUT_MS] caps the worst case where Compose drops the host
 * across an Activity recreation between request-emit and result. Mirrors the
 * [com.kofikodr.kofipod.opml.AndroidOpmlFilePort] pattern exactly.
 */
class AndroidBackupFilePort(
    private val context: Context,
) : BackupFilePort {
    data class FolderPickRequest(
        val deferred: CompletableDeferred<String?>,
    )

    data class RestorePickRequest(
        val deferred: CompletableDeferred<ByteArray?>,
    )

    private val _folderPicks =
        MutableSharedFlow<FolderPickRequest>(replay = 0, extraBufferCapacity = 1)
    val folderPicks: SharedFlow<FolderPickRequest> = _folderPicks.asSharedFlow()

    private val _restorePicks =
        MutableSharedFlow<RestorePickRequest>(replay = 0, extraBufferCapacity = 1)
    val restorePicks: SharedFlow<RestorePickRequest> = _restorePicks.asSharedFlow()

    override suspend fun pickFolder(): String? {
        val deferred = CompletableDeferred<String?>()
        _folderPicks.emit(FolderPickRequest(deferred))
        return withTimeout(PICKER_TIMEOUT_MS) { deferred.await() }
    }

    override suspend fun pickAndReadBackup(): ByteArray? {
        val deferred = CompletableDeferred<ByteArray?>()
        _restorePicks.emit(RestorePickRequest(deferred))
        return withTimeout(PICKER_TIMEOUT_MS) { deferred.await() }
    }

    /**
     * Write [content] under the persisted [treeUri]. We don't go through the picker
     * because the user already gave us a persisted permission grant when they picked
     * the folder — writing here is identical to writing to any other file URI.
     *
     * Atomic-ish replace: write to a temp filename first, then once the bytes are
     * fully on disk, delete the previous backup (if any) and rename the temp file
     * into place. If anything fails before the rename, the previous backup survives
     * untouched — the user never ends up with neither an old nor a new file. Some
     * providers (Drive, notably) reject truncate-and-rewrite, so this also avoids
     * that path. The whole operation runs on [Dispatchers.IO].
     */
    override suspend fun writeBackup(
        treeUri: String,
        content: ByteArray,
    ): Unit =
        withContext(Dispatchers.IO) {
            val tree =
                DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                    ?: error("Backup folder URI couldn't be resolved")
            if (!tree.canWrite()) {
                // Most commonly fires when the user revoked access in the storage provider's
                // app; surface as a SecurityException so the controller maps to the
                // "folder no longer accessible" copy.
                throw SecurityException("Backup folder is no longer writable")
            }

            // Clean up any leftover temp from a previous interrupted run before we start.
            tree.findFile(BACKUP_TEMP_FILENAME)?.delete()
            val tempFile =
                tree.createFile(BACKUP_MIME, BACKUP_TEMP_FILENAME)
                    ?: error("Couldn't create backup file in folder")
            runCatching {
                context.contentResolver.openOutputStream(tempFile.uri, "w")?.use { stream ->
                    stream.write(content)
                    stream.flush()
                } ?: error("Couldn't open output stream for backup file")
            }.onFailure { t ->
                // Clean up the half-written temp so the next run starts clean.
                runCatching { tempFile.delete() }
                throw t
            }

            // Temp file is fully written. Now do the swap: delete previous, rename temp.
            // If renameTo fails (some providers don't implement it), fall back to
            // creating a new file with the final name and re-streaming. We never delete
            // the previous file before we have the new bytes confirmed on disk.
            val previous = tree.findFile(BACKUP_FILENAME)
            val renamed = runCatching { tempFile.renameTo(BACKUP_FILENAME) }.getOrDefault(false)
            if (renamed) {
                previous?.delete()
            } else {
                // Fallback: provider doesn't support rename. Stream the temp file's bytes
                // to a fresh document with the final name, then drop the temp.
                val target =
                    tree.createFile(BACKUP_MIME, "$BACKUP_FILENAME.new")
                        ?: error("Couldn't create backup file in folder")
                runCatching {
                    context.contentResolver.openOutputStream(target.uri, "w")?.use { it.write(content) }
                        ?: error("Couldn't open output stream for backup file")
                    previous?.delete()
                    target.renameTo(BACKUP_FILENAME)
                    tempFile.delete()
                }.onFailure { t ->
                    runCatching { target.delete() }
                    throw t
                }
            }
        }

    private companion object {
        const val PICKER_TIMEOUT_MS = 5L * 60_000L
        const val BACKUP_TEMP_FILENAME = "$BACKUP_FILENAME.tmp"
    }
}

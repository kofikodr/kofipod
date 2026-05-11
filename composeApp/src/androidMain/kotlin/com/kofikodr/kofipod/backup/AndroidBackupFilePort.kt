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
     * Write [content] under the persisted [treeUri] as [filename]. Caller picks the
     * filename (typically [currentBackupFilename] for a timestamped run). We don't go
     * through the picker because the user already gave us a persisted permission grant
     * when they picked the folder — writing here is identical to writing to any other
     * file URI.
     *
     * Atomic-ish write: stage to `<filename>.tmp` first, then rename into place once the
     * bytes are fully on disk. If the provider doesn't implement `renameTo`, fall back to
     * creating the target directly and copying the temp's bytes. If anything fails before
     * the rename, the previous file (if any) survives untouched. Retention pruning runs
     * separately via [listBackups] + [deleteBackup] so this method stays focused on
     * "produce one new file safely."
     */
    override suspend fun writeBackup(
        treeUri: String,
        filename: String,
        content: ByteArray,
    ): Unit =
        withContext(Dispatchers.IO) {
            val tree = resolveWritableTree(treeUri)
            val tempName = "$filename.tmp"

            // Clean up any leftover temp from a previous interrupted run before we start.
            tree.findFile(tempName)?.delete()
            val tempFile =
                tree.createFile(BACKUP_MIME, tempName)
                    ?: error("Couldn't create backup file in folder")
            runCatching {
                context.contentResolver.openOutputStream(tempFile.uri, "w")?.use { stream ->
                    stream.write(content)
                    stream.flush()
                } ?: error("Couldn't open output stream for backup file")
            }.onFailure { t ->
                runCatching { tempFile.delete() }
                throw t
            }

            // Temp is fully written. If a file with the final name already exists (rare —
            // the timestamp would have to collide to the second), drop it first so the
            // rename has a clean slot. Some providers silently disambiguate the rename
            // (e.g. `kofipod-backup-…(1).kpbak`) and still return true; we re-read
            // `tempFile.name` to verify the rename actually produced the requested name
            // and fall through to the copy path if not — that branch creates the target
            // by name directly, which providers honour even when rename does not.
            val existing = tree.findFile(filename)
            val renamedTrue = runCatching { tempFile.renameTo(filename) }.getOrDefault(false)
            val renamed = renamedTrue && tempFile.name == filename
            if (renamed) {
                existing?.delete()
            } else {
                // Provider doesn't support rename. Create the final file and re-stream.
                existing?.delete()
                val target =
                    tree.createFile(BACKUP_MIME, filename)
                        ?: error("Couldn't create backup file in folder")
                runCatching {
                    context.contentResolver.openOutputStream(target.uri, "w")?.use { it.write(content) }
                        ?: error("Couldn't open output stream for backup file")
                    tempFile.delete()
                }.onFailure { t ->
                    runCatching { target.delete() }
                    throw t
                }
            }
        }

    override suspend fun listBackups(treeUri: String): List<BackupFileInfo> =
        withContext(Dispatchers.IO) {
            val tree =
                runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull()
                    ?: return@withContext emptyList()
            // Best-effort — `listFiles()` is an O(N) ContentProvider scan on the SAF side.
            // We deliberately do not call canWrite() here because retention should still
            // surface existing files in a folder that's gone read-only (the UI will fail
            // a subsequent write loudly).
            runCatching {
                tree.listFiles()
                    .asSequence()
                    .mapNotNull { doc ->
                        val name = doc.name ?: return@mapNotNull null
                        if (!isRecognisedBackupFilename(name)) return@mapNotNull null
                        // `lastModified` can return 0 on some providers (Drive); the
                        // caller handles that via filename-timestamp fallback.
                        BackupFileInfo(filename = name, lastModifiedMs = doc.lastModified())
                    }
                    .toList()
            }.getOrDefault(emptyList())
        }

    override suspend fun deleteBackup(
        treeUri: String,
        filename: String,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val tree =
                runCatching { DocumentFile.fromTreeUri(context, Uri.parse(treeUri)) }.getOrNull()
                    ?: return@withContext false
            val target = runCatching { tree.findFile(filename) }.getOrNull() ?: return@withContext true
            runCatching { target.delete() }.getOrDefault(false)
        }

    private fun resolveWritableTree(treeUri: String): DocumentFile {
        val tree =
            DocumentFile.fromTreeUri(context, Uri.parse(treeUri))
                ?: error("Backup folder URI couldn't be resolved")
        if (!tree.canWrite()) {
            // Most commonly fires when the user revoked access in the storage provider's
            // app; surface as a SecurityException so the controller maps to the
            // "folder no longer accessible" copy.
            throw SecurityException("Backup folder is no longer writable")
        }
        return tree
    }

    private fun isRecognisedBackupFilename(name: String): Boolean =
        name == LEGACY_BACKUP_FILENAME ||
            (
                name.startsWith(BACKUP_FILENAME_PREFIX) &&
                    name.endsWith(BACKUP_FILENAME_SUFFIX) &&
                    !name.endsWith(".tmp") &&
                    !name.endsWith(".new")
            )

    private companion object {
        const val PICKER_TIMEOUT_MS = 5L * 60_000L
    }
}

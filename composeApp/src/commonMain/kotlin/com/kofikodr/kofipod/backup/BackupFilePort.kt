// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

/**
 * Surfaces the platform file-picker / SAF write surface to common code without pulling
 * Android types into commonMain. Mirrors [com.kofikodr.kofipod.opml.OpmlFilePort]'s shape.
 *
 * Android binds this to the flow-driven [com.kofikodr.kofipod.backup.AndroidBackupFilePort],
 * which is paired with a Compose host (`BackupPickerHost`) hoisted in
 * [com.kofikodr.kofipod.ui.shell.AppShell] so the SAF launchers stay rooted regardless of which
 * screen triggered the request.
 *
 * iOS binds a no-op so Koin's graph stays consistent across targets.
 */
interface BackupFilePort {
    /**
     * Open SAF's `OpenDocumentTree` picker. Returns the persisted tree URI as a string,
     * or `null` if the user cancelled. The Android binding takes a persistable URI
     * permission grant so the URI is usable across process and reboot.
     *
     * Throws on permission-grant failure (extremely rare — the system normally returns
     * a usable URI or the user cancels).
     */
    suspend fun pickFolder(): String?

    /**
     * Write [content] to [filename] inside the persisted tree URI. Overwrites if the
     * file already exists. The caller is responsible for choosing a filename — typically
     * [currentBackupFilename]. Throws on:
     *  - `SecurityException` if the URI was revoked (e.g. user removed access in the
     *    storage provider's app) — caller surfaces this as "folder no longer accessible".
     *  - `IOException` on out-of-space / network errors at the provider.
     */
    suspend fun writeBackup(
        treeUri: String,
        filename: String,
        content: ByteArray,
    )

    /**
     * List every `.kpbak` file in [treeUri] that this app recognises as a backup file —
     * the timestamped pattern emitted by [currentBackupFilename] plus the legacy single
     * filename [LEGACY_BACKUP_FILENAME]. Order is unspecified. Returns an empty list if
     * the folder is empty, contains no backups, or can't be read. Used by retention.
     */
    suspend fun listBackups(treeUri: String): List<BackupFileInfo>

    /**
     * Delete the file named [filename] from [treeUri]. Returns true when the provider
     * confirmed deletion (or the file was already absent), false on provider refusal.
     * Never throws — callers swallow failures since retention is best-effort.
     */
    suspend fun deleteBackup(
        treeUri: String,
        filename: String,
    ): Boolean

    /**
     * Open SAF's `OpenDocument` picker filtered to the backup MIME type. Returns the
     * full byte content of the picked file, or `null` if the user cancelled.
     *
     * The picker is intentionally independent of the persisted tree URI — the user can
     * restore from a file in any folder, on any provider, even if it differs from the
     * backup destination.
     */
    suspend fun pickAndReadBackup(): ByteArray?
}

/**
 * Lightweight descriptor for a backup file in the SAF folder. [lastModifiedMs] is the
 * provider's reported modification time, or 0 when the provider doesn't expose one
 * (Drive, some legacy providers); callers fall back to [parseBackupFilenameTimestamp]
 * in that case.
 */
data class BackupFileInfo(
    val filename: String,
    val lastModifiedMs: Long,
)

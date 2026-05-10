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
     * Write [content] to the file [BACKUP_FILENAME] inside the persisted tree URI.
     * Overwrites if the file already exists. Throws on:
     *  - `SecurityException` if the URI was revoked (e.g. user removed access in the
     *    storage provider's app) — caller surfaces this as "folder no longer accessible".
     *  - `IOException` on out-of-space / network errors at the provider.
     */
    suspend fun writeBackup(
        treeUri: String,
        content: ByteArray,
    )

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

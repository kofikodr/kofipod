// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.backup

import kotlinx.coroutines.flow.Flow

/**
 * Persistent home for SAF-backup state: the picked tree URI, the last successful backup
 * timestamp, and the pending-restore filename used by [app.kofipod.backup.PendingRestore]
 * (Slice 2) to hand a staged DB across an `exitProcess`.
 *
 * On Android these all live in the same `kofipod_local` SharedPreferences file (already
 * excluded from Auto Backup via `backup_rules*.xml`). That's the right home — the URI is
 * device-local (re-pinning it on a fresh device is the user's job, exactly as we re-pin
 * the BYOK Gemini key), and Auto Backup must not carry the URI to a new install where
 * it would be useless.
 *
 * Plain interface (not `expect class`) so JVM unit tests can fake it without standing
 * up Android Context / SharedPreferences. iOS gets [IosBackupFolderStore]; Android gets
 * [AndroidBackupFolderStore].
 */
interface BackupFolderStore {
    fun treeUriNow(): String?

    fun setTreeUri(uri: String?)

    fun treeUriFlow(): Flow<String?>

    fun lastBackupAtNow(): Long?

    fun setLastBackupAt(ms: Long?)

    fun lastBackupAtFlow(): Flow<Long?>

    fun pendingRestoreFilenameNow(): String?

    fun setPendingRestoreFilename(name: String?)

    /**
     * Resolves a tree URI string to the user-visible folder name (e.g. "Backups",
     * "Kofipod"). Returns `null` if the URI can't be resolved (provider revoked, file
     * deleted) — the UI surfaces "Folder unavailable" in that case. Android uses
     * `DocumentFile.fromTreeUri(...).name`; iOS always returns `null`.
     */
    fun displayNameForTreeUri(uri: String): String?

    /**
     * Read-and-clear the one-shot "a restore just completed" flag set by
     * `PendingRestore.consumeIfPresent` after it copies the staged DB into place.
     * Returns `true` exactly once per restore — subsequent calls return `false` even
     * within the same process.
     */
    fun consumeRestoreCompletedFlag(): Boolean
}

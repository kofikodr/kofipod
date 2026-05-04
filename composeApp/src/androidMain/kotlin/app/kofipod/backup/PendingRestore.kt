// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.backup

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Consumes a staged restore payload before [app.kofipod.KofipodApplication] starts Koin.
 *
 * The flow:
 *  1. [BackupController.confirmRestore] copies the picked backup's DB bytes to
 *     `filesDir/restore.tmp` and writes the filename into [BackupFolderStore]'s
 *     `backup_pending_restore_filename` pref. Then it `exitProcess(0)`s.
 *  2. On the next cold start, [consumeIfPresent] reads that pref directly (before Koin
 *     stands up the SQLDelight driver) and copies the staged file over
 *     `kofipod.db`, deleting any `-shm` / `-wal` siblings so SQLite recreates them
 *     fresh against the new file.
 *  3. The pref is cleared, the staged file deleted, a one-shot
 *     `backup_restore_completed = true` flag is set so AppShell can surface a
 *     "Library restored" snackbar on first composition.
 *
 * Wrapped in `runCatching` end-to-end: a stuck pending-restore can never block app
 * startup. If anything throws here, we log + clear the flag and proceed normally — the
 * user can re-trigger the restore from Settings.
 */
internal object PendingRestore {
    const val PREF_FILE = "kofipod_local"
    private const val PREF_KEY_PENDING = "backup_pending_restore_filename"
    private const val PREF_KEY_COMPLETED = "backup_restore_completed"
    private const val DB_NAME = "kofipod.db"
    private const val LOG_TAG = "Kofipod-Backup"

    /**
     * Returns `true` if a restore was consumed (caller may want to clear in-memory
     * state too, but in practice the process will be moments old at this point).
     */
    fun consumeIfPresent(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val staged = prefs.getString(PREF_KEY_PENDING, null)?.takeIf { it.isNotEmpty() } ?: return false

        val ok =
            runCatching {
                val stagedFile = File(context.filesDir, staged)
                if (!stagedFile.exists() || stagedFile.length() == 0L) {
                    Log.w(LOG_TAG, "pending restore present but staged file missing/empty; skipping")
                    return@runCatching false
                }
                val dbFile = context.getDatabasePath(DB_NAME)
                dbFile.parentFile?.mkdirs()
                stagedFile.copyTo(dbFile, overwrite = true)
                // SQLite WAL/shm hold journal state for the OLD db file; deleting them
                // forces SQLite to recreate them against our new on-disk image.
                File(dbFile.absolutePath + "-wal").delete()
                File(dbFile.absolutePath + "-shm").delete()
                stagedFile.delete()
                // Length only, no path. Path would leak the user's data dir.
                Log.i(LOG_TAG, "restore consumed (${dbFile.length()} bytes)")
                true
            }.getOrElse { t ->
                // Log only the exception class — never `t.message`. SAF / SQLite
                // exceptions frequently include the URI or file path in the message,
                // and the spec policy ("no file paths or URI strings in any log")
                // applies just as much to the consume path as to the controller.
                Log.e(LOG_TAG, "pending restore failed: ${t::class.simpleName}")
                false
            }

        prefs.edit().apply {
            remove(PREF_KEY_PENDING)
            if (ok) putBoolean(PREF_KEY_COMPLETED, true)
            apply()
        }
        return ok
    }

    /**
     * Reads and clears the one-shot "restore just finished" flag. Called by AppShell on
     * first composition; if `true`, the shell emits a "Library restored" snackbar.
     */
    fun consumeCompletedFlag(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val flag = prefs.getBoolean(PREF_KEY_COMPLETED, false)
        if (flag) prefs.edit().remove(PREF_KEY_COMPLETED).apply()
        return flag
    }
}

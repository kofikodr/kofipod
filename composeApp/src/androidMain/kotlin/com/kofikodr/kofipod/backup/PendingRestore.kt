// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import java.io.File

/**
 * Consumes a staged restore payload before [com.kofikodr.kofipod.KofipodApplication] starts Koin.
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
                // Refuse anything that isn't a structurally complete SQLite file. A
                // process kill during staging used to be able to leave a truncated
                // restore.tmp that passed a bare length>0 check and then clobbered the
                // live DB with an unopenable image (issue #17). Staging is now atomic,
                // but we still validate before touching kofipod.db as defence-in-depth.
                if (!isCompleteSqliteFile(stagedFile)) {
                    Log.w(LOG_TAG, "pending restore staged file invalid/incomplete; skipping (live DB untouched)")
                    stagedFile.delete()
                    return@runCatching false
                }
                val dbFile = context.getDatabasePath(DB_NAME)
                dbFile.parentFile?.mkdirs()
                // Copy to a sibling temp, then atomically rename over the live DB, so a
                // kill mid-copy can't leave kofipod.db itself truncated. Safe here: this
                // runs before Koin stands up the SQLDelight driver, so no connection is
                // open on the live DB.
                val newDbFile = File(dbFile.absolutePath + ".new")
                newDbFile.delete()
                stagedFile.copyTo(newDbFile, overwrite = true)
                if (!newDbFile.renameTo(dbFile)) {
                    newDbFile.delete()
                    error("Couldn't move restored DB into place")
                }
                // SQLite WAL/shm hold journal state for the OLD db file; deleting them
                // forces SQLite to recreate them against our new on-disk image.
                File(dbFile.absolutePath + "-wal").delete()
                File(dbFile.absolutePath + "-shm").delete()
                stagedFile.delete()
                scrubTransientState(dbFile)
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
     * Drop rows from tables whose contents only make sense on the device that produced
     * the backup: `Download` entries point at `localPath`s that don't exist on this
     * install, and `PlaybackState` resume positions reference audio that may also be
     * gone. Run this against the just-restored DB *before* SQLDelight stands up so the
     * first repository read sees a clean slate.
     *
     * Wrapped in its own `runCatching`: if the scrub throws (corrupt DB, missing table
     * because of a schema mismatch we somehow let through, etc.) we still want the
     * restore itself to count as successful — the caller has already overwritten
     * `kofipod.db`. Worst case: stale `Download` / `PlaybackState` rows survive into the
     * restored session (e.g. the Downloads screen shows phantom entries) until the user
     * clears them; the restored library data itself is intact.
     */
    private fun scrubTransientState(dbFile: File) {
        runCatching {
            SQLiteDatabase.openDatabase(
                dbFile.absolutePath,
                // factory =
                null,
                SQLiteDatabase.OPEN_READWRITE,
            ).use { db ->
                // Atomic: a kill between the two DELETEs would otherwise leave
                // PlaybackState scrubbed but Download surviving (or vice versa).
                db.beginTransaction()
                try {
                    db.execSQL("DELETE FROM Download")
                    db.execSQL("DELETE FROM PlaybackState")
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            }
        }.onFailure { t ->
            Log.w(LOG_TAG, "post-restore scrub failed: ${t::class.simpleName}")
        }
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

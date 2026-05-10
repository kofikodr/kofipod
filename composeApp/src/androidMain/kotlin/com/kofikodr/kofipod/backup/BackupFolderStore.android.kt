// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/**
 * SharedPreferences-backed implementation of [BackupFolderStore].
 *
 * Lives in the same `kofipod_local` file as the downloaded-APK pointer
 * ([com.kofikodr.kofipod.update.AndroidLocalApkPathStore]) — both are device-local pointers that
 * must NOT travel via Auto Backup or device transfer. The `<exclude>` for this file is
 * already present in `composeApp/src/androidMain/res/xml/backup_rules*.xml`; adding new
 * keys to the same file inherits that exclusion automatically.
 */
class AndroidBackupFolderStore(private val context: Context) : BackupFolderStore {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun treeUriNow(): String? = prefs.getString(KEY_TREE_URI, null)?.takeIf { it.isNotEmpty() }

    override fun setTreeUri(uri: String?) {
        prefs.edit().apply {
            if (uri.isNullOrEmpty()) remove(KEY_TREE_URI) else putString(KEY_TREE_URI, uri)
            apply()
        }
    }

    override fun treeUriFlow(): Flow<String?> = stringFlow(KEY_TREE_URI) { treeUriNow() }

    override fun lastBackupAtNow(): Long? = prefs.getLong(KEY_LAST_BACKUP_MS, 0L).takeIf { it > 0L }

    override fun setLastBackupAt(ms: Long?) {
        prefs.edit().apply {
            if (ms == null) remove(KEY_LAST_BACKUP_MS) else putLong(KEY_LAST_BACKUP_MS, ms)
            apply()
        }
    }

    override fun lastBackupAtFlow(): Flow<Long?> = stringFlow(KEY_LAST_BACKUP_MS) { lastBackupAtNow() }

    override fun pendingRestoreFilenameNow(): String? = prefs.getString(KEY_PENDING_RESTORE, null)?.takeIf { it.isNotEmpty() }

    override fun setPendingRestoreFilename(name: String?) {
        prefs.edit().apply {
            if (name.isNullOrEmpty()) remove(KEY_PENDING_RESTORE) else putString(KEY_PENDING_RESTORE, name)
            apply()
        }
    }

    override fun displayNameForTreeUri(uri: String): String? =
        runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri))?.name }.getOrNull()

    override fun consumeRestoreCompletedFlag(): Boolean {
        val flag = prefs.getBoolean(KEY_RESTORE_COMPLETED, false)
        if (flag) prefs.edit().remove(KEY_RESTORE_COMPLETED).apply()
        return flag
    }

    private fun <T> stringFlow(
        key: String,
        read: () -> T?,
    ): Flow<T?> =
        callbackFlow {
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                    if (changedKey == key || changedKey == null) {
                        trySend(read())
                    }
                }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
            .onStart { emit(read()) }
            .distinctUntilChanged()

    companion object {
        // File name must stay in sync with the <exclude> in res/xml/backup_rules*.xml.
        const val FILE_NAME = "kofipod_local"

        // PendingRestore (Slice 2) reads this key directly before Koin starts, so it
        // must stay in sync with PendingRestore.PREF_KEY.
        const val KEY_PENDING_RESTORE = "backup_pending_restore_filename"

        // Must stay in sync with PendingRestore.PREF_KEY_COMPLETED.
        private const val KEY_RESTORE_COMPLETED = "backup_restore_completed"
        private const val KEY_TREE_URI = "backup_folder_uri"
        private const val KEY_LAST_BACKUP_MS = "backup_last_at_ms"
    }
}

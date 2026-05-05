// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.update

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

class AndroidLocalApkPathStore(context: Context) : LocalApkPathStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun pathNow(): String? = prefs.getString(KEY_PATH, null)?.takeIf { it.isNotEmpty() }

    override fun setPath(path: String?) {
        prefs.edit().apply {
            if (path.isNullOrEmpty()) remove(KEY_PATH) else putString(KEY_PATH, path)
            apply()
        }
    }

    override fun pathFlow(): Flow<String?> =
        callbackFlow {
            val listener =
                SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
                    if (changedKey == KEY_PATH || changedKey == null) {
                        trySend(pathNow())
                    }
                }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
            .onStart { emit(pathNow()) }
            .distinctUntilChanged()

    companion object {
        // File name must stay in sync with the <exclude> in res/xml/backup_rules*.xml.
        const val FILE_NAME = "kofipod_local"
        private const val KEY_PATH = "downloaded_apk_path"
    }
}

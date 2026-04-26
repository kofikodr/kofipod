// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS_FILE = "kofipod_secure"
private const val KEY_GEMINI_API_KEY = "gemini_api_key"

actual class KeyVault(private val context: Context) {
    private val prefs by lazy {
        val masterKey =
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    actual suspend fun get(): String? =
        withContext(Dispatchers.IO) {
            prefs.getString(KEY_GEMINI_API_KEY, null)?.takeIf { it.isNotBlank() }
        }

    actual suspend fun set(value: String) =
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_GEMINI_API_KEY, value).apply()
        }

    actual suspend fun clear() =
        withContext(Dispatchers.IO) {
            prefs.edit().remove(KEY_GEMINI_API_KEY).apply()
        }
}

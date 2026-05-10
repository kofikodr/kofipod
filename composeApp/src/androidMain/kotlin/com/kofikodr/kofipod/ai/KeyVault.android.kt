// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS_FILE = "kofipod_secure"
private const val KEY_GEMINI_API_KEY = "gemini_api_key"

/**
 * EncryptedSharedPreferences-backed [KeyVault] implementation. The prefs file
 * (`kofipod_secure`) is also excluded from Auto Backup via `backup_rules.xml`
 * so the key never leaves this device.
 */
class AndroidKeyVault(private val context: Context) : KeyVault {
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

    override suspend fun get(): String? =
        withContext(Dispatchers.IO) {
            prefs.getString(KEY_GEMINI_API_KEY, null)?.takeIf { it.isNotBlank() }
        }

    // commit() (synchronous) instead of apply() (deferred): AiConfigRepository
    // flips its in-memory `keyConfigured` flag immediately after this returns.
    // If apply()'s deferred write loses to a process death, the flag and the
    // disk would disagree on next launch — connect appears to "stick" but the
    // key isn't there, or disconnect appears to clear but the key persists.
    override suspend fun set(value: String) =
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_GEMINI_API_KEY, value).commit()
            Unit
        }

    override suspend fun clear() =
        withContext(Dispatchers.IO) {
            prefs.edit().remove(KEY_GEMINI_API_KEY).commit()
            Unit
        }
}

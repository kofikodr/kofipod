// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS_FILE = "kofipod_secure" // shared with the Gemini key; already backup-excluded
private const val KEY = "podcast_index_key"
private const val SECRET = "podcast_index_secret"

/**
 * EncryptedSharedPreferences-backed [PodcastIndexCredentialStore]. Reuses the `kofipod_secure`
 * prefs file (already excluded from Auto Backup) so the credentials never leave this device.
 * Returns creds only when BOTH key and secret are present and non-blank. Uses commit() so the
 * config repo's flag never gets ahead of a failed write.
 */
class AndroidPodcastIndexCredentialStore(private val context: Context) : PodcastIndexCredentialStore {
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun get(): PodcastIndexCreds? =
        withContext(Dispatchers.IO) {
            val k = prefs.getString(KEY, null)?.takeIf { it.isNotBlank() }
            val s = prefs.getString(SECRET, null)?.takeIf { it.isNotBlank() }
            if (k != null && s != null) PodcastIndexCreds(k, s) else null
        }

    override suspend fun set(creds: PodcastIndexCreds) =
        withContext(Dispatchers.IO) {
            val ok = prefs.edit().putString(KEY, creds.key).putString(SECRET, creds.secret).commit()
            check(ok) { "Failed to persist Podcast Index credentials to encrypted preferences" }
        }

    override suspend fun clear() =
        withContext(Dispatchers.IO) {
            val ok = prefs.edit().remove(KEY).remove(SECRET).commit()
            check(ok) { "Failed to clear Podcast Index credentials from encrypted preferences" }
        }
}

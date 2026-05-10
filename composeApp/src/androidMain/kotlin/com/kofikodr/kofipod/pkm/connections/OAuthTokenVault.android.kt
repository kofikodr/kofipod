// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.pkm.connections

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS_FILE = "kofipod_secure"

actual class OAuthTokenVaultImpl(private val context: Context) : OAuthTokenVault {
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

    actual override suspend fun put(
        key: String,
        token: String,
    ) = withContext(Dispatchers.IO) {
        prefs.edit().putString(key, token).commit()
        Unit
    }

    actual override suspend fun get(key: String): String? =
        withContext(Dispatchers.IO) {
            prefs.getString(key, null)?.takeIf { it.isNotBlank() }
        }

    actual override suspend fun clear(key: String) =
        withContext(Dispatchers.IO) {
            prefs.edit().remove(key).commit()
            Unit
        }
}

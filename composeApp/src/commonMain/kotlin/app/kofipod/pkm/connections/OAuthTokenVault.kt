// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.connections

/**
 * Multi-key encrypted store for short bearer secrets (Readwise API token,
 * Notion OAuth refresh token, etc.). Backed by the existing
 * `kofipod_secure.xml` EncryptedSharedPreferences file on Android, which is
 * already excluded from Auto Backup. Keys are caller-defined opaque strings,
 * e.g. `"readwise.token"`, `"notion.refresh"`.
 */
expect class OAuthTokenVault {
    suspend fun put(
        key: String,
        token: String,
    )

    suspend fun get(key: String): String?

    suspend fun clear(key: String)
}

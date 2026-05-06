// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.connections

/**
 * Multi-key encrypted store for short bearer secrets (Readwise API token,
 * Notion OAuth refresh token, etc.). Backed by the existing
 * `kofipod_secure.xml` EncryptedSharedPreferences file on Android, which is
 * already excluded from Auto Backup. Keys are caller-defined opaque strings,
 * e.g. `"readwise.token"`, `"notion.refresh"`.
 *
 * Modeled as an interface so repositories that depend on it can be unit-tested
 * with a simple in-memory fake. The concrete platform implementations live in
 * [OAuthTokenVaultImpl].
 */
interface OAuthTokenVault {
    suspend fun put(
        key: String,
        token: String,
    )

    suspend fun get(key: String): String?

    suspend fun clear(key: String)
}

/**
 * Platform-backed concrete vault. Android wraps the
 * `kofipod_secure` EncryptedSharedPreferences file; iOS currently uses an
 * in-memory store (Slice 6 ships Android only — iOS will graduate to Keychain
 * when the iOS surface lands).
 */
expect class OAuthTokenVaultImpl : OAuthTokenVault {
    override suspend fun put(
        key: String,
        token: String,
    )

    override suspend fun get(key: String): String?

    override suspend fun clear(key: String)
}

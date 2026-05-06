// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.connections

actual class OAuthTokenVaultImpl : OAuthTokenVault {
    private val store = mutableMapOf<String, String>()

    actual override suspend fun put(
        key: String,
        token: String,
    ) {
        store[key] = token
    }

    actual override suspend fun get(key: String): String? = store[key]

    actual override suspend fun clear(key: String) {
        store.remove(key)
    }
}

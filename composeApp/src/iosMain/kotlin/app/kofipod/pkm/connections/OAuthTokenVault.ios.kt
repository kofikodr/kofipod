// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm.connections

actual class OAuthTokenVault {
    private val store = mutableMapOf<String, String>()

    actual suspend fun put(
        key: String,
        token: String,
    ) {
        store[key] = token
    }

    actual suspend fun get(key: String): String? = store[key]

    actual suspend fun clear(key: String) {
        store.remove(key)
    }
}

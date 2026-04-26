// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

/**
 * Per-device secure store for the user's BYOK Gemini API key.
 *
 * Android backs this with EncryptedSharedPreferences (file kofipod_secure), excluded
 * from Auto Backup so the key never syncs to a different device. iOS is a stub for now.
 */
expect class KeyVault {
    suspend fun get(): String?

    suspend fun set(value: String)

    suspend fun clear()
}

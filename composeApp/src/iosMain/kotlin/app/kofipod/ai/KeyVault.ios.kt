// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

// TODO(ios): back this with Keychain via platform.Security when iOS becomes a first-class target.
// For now AI features are Android-only and this stub returns null so commonMain code compiles.
actual class KeyVault {
    actual suspend fun get(): String? = null

    actual suspend fun set(value: String) {}

    actual suspend fun clear() {}
}

// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

/**
 * iOS [KeyVault] stub. AI features are Android-only today; this exists so
 * commonMain code that depends on `KeyVault` compiles for the iOS targets.
 *
 * TODO(ios): back this with Keychain via `platform.Security` when iOS becomes
 * a first-class target.
 */
class IosKeyVaultStub : KeyVault {
    override suspend fun get(): String? = null

    override suspend fun set(value: String) = Unit

    override suspend fun clear() = Unit
}

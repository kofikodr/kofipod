// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import com.kofikodr.kofipod.data.repo.SettingsRepository
import com.kofikodr.kofipod.testing.inMemoryDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [AiConfigRepository]'s storage contract. Two invariants matter most for the
 * BYOK security model:
 *
 *  1. [AiConfigRepository.disconnect] actually erases the key from the underlying
 *     [KeyVault]. This is the entire promise of the "Disconnect" button — the user
 *     must not be able to leave a key behind by accident.
 *  2. The reactive `isKeyConfigured` flow hydrates correctly from a pre-populated
 *     vault on startup, otherwise an existing-key user opens the app and sees
 *     "Connect" instead of "Connected".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AiConfigRepositoryTest {
    @Test
    fun init_setsKeyConfiguredTrue_whenVaultAlreadyHasAKey() =
        runTest {
            val vault = FakeKeyVault(initial = "pre-existing-key")
            val settings = SettingsRepository(inMemoryDatabase())
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            val repo = AiConfigRepository(keyVault = vault, settings = settings, appScope = scope)
            runCurrent()

            assertTrue(
                repo.isKeyConfigured().value,
                "Existing-key install must hydrate to true so the UI shows 'Connected' on first frame",
            )
        }

    @Test
    fun init_setsKeyConfiguredFalse_whenVaultIsEmpty() =
        runTest {
            val vault = FakeKeyVault(initial = null)
            val settings = SettingsRepository(inMemoryDatabase())
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            val repo = AiConfigRepository(keyVault = vault, settings = settings, appScope = scope)
            runCurrent()

            assertFalse(repo.isKeyConfigured().value)
        }

    @Test
    fun init_treatsBlankVaultValue_asNotConfigured() =
        runTest {
            // Defensive: a corrupted prefs file or partial write could leave a blank
            // string in the vault. We must not flip the user into "connected" without
            // a usable key — Gemini would reject the empty string and show a confusing
            // error in the UI.
            val vault = FakeKeyVault(initial = "   ")
            val settings = SettingsRepository(inMemoryDatabase())
            val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

            val repo = AiConfigRepository(keyVault = vault, settings = settings, appScope = scope)
            runCurrent()

            assertFalse(repo.isKeyConfigured().value, "Blank string in vault must be treated as no key")
        }

    @Test
    fun setKey_persistsToVault_andFlipsKeyConfiguredToTrue() =
        runTest {
            val vault = FakeKeyVault(initial = null)
            val repo = newRepo(vault, this)

            repo.setKey("user-pasted-key")

            assertEquals("user-pasted-key", vault.stored, "setKey must hand the value to KeyVault.set()")
            assertTrue(repo.isKeyConfigured().value, "isKeyConfigured must observe the new state immediately")
            assertEquals("user-pasted-key", repo.currentKey(), "currentKey() must return what was just stored")
        }

    @Test
    fun disconnect_erasesKeyFromVault_andFlipsKeyConfiguredToFalse() =
        runTest {
            // The BYOK contract: when the user disconnects, the key is gone. No
            // process-memory copy lingers, no stale prefs entry, no "I'll get to it
            // next launch" semantics. Verifying both the vault state AND the
            // exposed flow because either being stale is a user-visible bug.
            val vault = FakeKeyVault(initial = "old-key")
            val repo = newRepo(vault, this)

            repo.disconnect()

            assertNull(vault.stored, "disconnect must call KeyVault.clear() — the key must not survive")
            assertFalse(repo.isKeyConfigured().value, "isKeyConfigured must reflect disconnect synchronously")
            assertNull(repo.currentKey(), "currentKey must return null after disconnect")
        }

    @Test
    fun model_reflectsSettingsRepositoryRoundTrip() =
        runTest {
            val repo = newRepo(FakeKeyVault(initial = null), this)

            // Default before any write — Flash, per GeminiModels.
            assertEquals(GeminiModel.Flash, repo.model().first())

            repo.setModel(GeminiModel.FlashLite)
            assertEquals(GeminiModel.FlashLite, repo.model().first(), "setModel must round-trip through Settings")
        }

    private fun newRepo(
        vault: KeyVault,
        scope: kotlinx.coroutines.test.TestScope,
    ): AiConfigRepository {
        val coroutineScope = CoroutineScope(UnconfinedTestDispatcher(scope.testScheduler))
        return AiConfigRepository(
            keyVault = vault,
            settings = SettingsRepository(inMemoryDatabase()),
            appScope = coroutineScope,
        ).also { scope.testScheduler.runCurrent() }
    }
}

/**
 * Test-only [KeyVault] backed by a single nullable string. Captures the same
 * `get/set/clear` semantics the Android `EncryptedSharedPreferences` impl
 * provides, without standing up Robolectric or AndroidX Security.
 */
private class FakeKeyVault(initial: String? = null) : KeyVault {
    var stored: String? = initial
        private set

    override suspend fun get(): String? = stored?.takeIf { it.isNotBlank() }

    override suspend fun set(value: String) {
        stored = value
    }

    override suspend fun clear() {
        stored = null
    }
}

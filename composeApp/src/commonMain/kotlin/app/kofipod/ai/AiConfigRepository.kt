// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import app.kofipod.data.repo.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Reactive façade over [KeyVault] and the persisted AI model preference. Both the
 * Settings entry row and the AI Setup screen subscribe here so connect/disconnect
 * updates land everywhere without us re-reading EncryptedSharedPreferences on each
 * recomposition.
 *
 * The current key never leaves this class — [GeminiClient] receives it once per
 * request via [currentKey] and we do not memoise it in process memory.
 */
class AiConfigRepository(
    private val keyVault: KeyVault,
    private val settings: SettingsRepository,
    appScope: CoroutineScope,
) {
    private val keyConfigured = MutableStateFlow(false)

    init {
        // Hydrate once on startup. Reads from EncryptedSharedPreferences are I/O-bound,
        // so we don't block construction. EncryptedSharedPreferences.create() can throw
        // on first init (Keystore locked, fresh boot, key rotation race) — swallow
        // silently means `keyConfigured` stays false and the user sees an unconnected
        // state with no path forward. Log the class name so a logcat trace is enough
        // to diagnose without leaking the key or any preference contents.
        appScope.launch {
            keyConfigured.value =
                runCatching { !keyVault.get().isNullOrBlank() }
                    .getOrElse {
                        println("Kofipod-AI: keyVault hydration failed: ${it::class.simpleName}")
                        false
                    }
        }
    }

    fun isKeyConfigured(): StateFlow<Boolean> = keyConfigured.asStateFlow()

    fun model(): Flow<GeminiModel> = settings.aiModel()

    /** Returns the raw key for one-shot use by [GeminiClient]. Never log or persist. */
    suspend fun currentKey(): String? = keyVault.get()

    suspend fun setKey(value: String) {
        keyVault.set(value)
        keyConfigured.value = true
    }

    suspend fun setModel(model: GeminiModel) {
        settings.setAiModel(model)
    }

    suspend fun disconnect() {
        keyVault.clear()
        keyConfigured.value = false
    }
}

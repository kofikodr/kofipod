// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Reactive façade over [PodcastIndexCredentialStore] for the FOSS BYOK flow. Mirrors
 * AiConfigRepository: hydrate once on startup, keep the in-memory flag and the encrypted
 * store in sync, and never memoise the raw secret in process memory.
 */
class PodcastIndexConfigRepository(
    private val store: PodcastIndexCredentialStore,
    appScope: CoroutineScope,
) {
    private val configured = MutableStateFlow(false)

    init {
        appScope.launch {
            configured.value =
                runCatching { store.get()?.isUsable == true }
                    .getOrElse {
                        println("Kofipod-PI: credential hydration failed: ${it::class.simpleName}")
                        false
                    }
        }
    }

    fun isConfigured(): StateFlow<Boolean> = configured.asStateFlow()

    /** One-shot read for the client provider. Never log or persist. */
    suspend fun currentCreds(): PodcastIndexCreds? = store.get()?.takeIf { it.isUsable }

    suspend fun setCredentials(creds: PodcastIndexCreds) {
        store.set(creds)
        configured.value = true
    }

    suspend fun disconnect() {
        store.clear()
        configured.value = false
    }
}

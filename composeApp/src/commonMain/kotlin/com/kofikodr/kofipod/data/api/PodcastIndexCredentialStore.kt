// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

/**
 * On-device encrypted store for user-supplied Podcast Index credentials (FOSS BYOK).
 * Mirrors [com.kofikodr.kofipod.ai.KeyVault] but holds a key+secret pair. Returns null
 * unless BOTH values are present and non-blank.
 */
interface PodcastIndexCredentialStore {
    suspend fun get(): PodcastIndexCreds?

    suspend fun set(creds: PodcastIndexCreds)

    suspend fun clear()
}

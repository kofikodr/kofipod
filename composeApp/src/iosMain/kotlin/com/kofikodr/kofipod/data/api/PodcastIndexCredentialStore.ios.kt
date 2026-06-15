// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

/** iOS has no real Podcast Index integration yet; BYOK is a no-op (matches IosKeyVaultStub). */
class IosPodcastIndexCredentialStoreStub : PodcastIndexCredentialStore {
    override suspend fun get(): PodcastIndexCreds? = null

    override suspend fun set(creds: PodcastIndexCreds) = Unit

    override suspend fun clear() = Unit
}

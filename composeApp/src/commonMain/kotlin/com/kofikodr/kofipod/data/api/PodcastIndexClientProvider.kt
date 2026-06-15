// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import com.kofikodr.kofipod.config.BuildKonfig
import com.mr3y.podcastindex.PodcastIndexClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.mr3y.podcastindex.ktor3.PodcastIndexClient as Ktor3PodcastIndexClient

/**
 * Supplies a client built from the currently-effective credentials, rebuilding only when those
 * credentials change (e.g. the user connects/disconnects BYOK creds). The SDK client captures
 * key/secret at construction, so rebuilding is how credential changes take effect without an app
 * restart. A [Mutex] keeps concurrent callers from racing the rebuild.
 *
 * The type parameter [C] is `PodcastIndexClient` in production; tests inject a simpler type to
 * avoid constructing the real SDK client (which is a final class, not an interface).
 */
class PodcastIndexClientProvider<C : Any>(
    private val resolve: suspend () -> PodcastIndexCreds,
    private val build: (PodcastIndexCreds) -> C,
) {
    private val lock = Mutex()
    private var builtFor: PodcastIndexCreds? = null
    private var current: C? = null

    suspend fun get(): C {
        val creds = resolve()
        lock.withLock {
            val existing = current
            if (existing != null && builtFor == creds) return existing
            return build(creds).also {
                current = it
                builtFor = creds
            }
        }
    }

    companion object {
        /**
         * Production factory: resolves creds via [resolve] and builds the real
         * [PodcastIndexClient] SDK instance.
         */
        operator fun invoke(resolve: suspend () -> PodcastIndexCreds): PodcastIndexClientProvider<PodcastIndexClient> =
            PodcastIndexClientProvider(
                resolve = resolve,
                build = { creds ->
                    Ktor3PodcastIndexClient(
                        authKey = creds.key,
                        authSecret = creds.secret,
                        userAgent = BuildKonfig.USER_AGENT,
                    )
                },
            )
    }
}

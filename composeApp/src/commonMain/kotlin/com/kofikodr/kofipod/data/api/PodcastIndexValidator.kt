// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import com.kofikodr.kofipod.config.BuildKonfig
import com.mr3y.podcastindex.ktor3.PodcastIndexClient as Ktor3PodcastIndexClient

enum class PodcastIndexValidation { Valid, Invalid, NetworkError }

interface PodcastIndexValidator {
    suspend fun validate(creds: PodcastIndexCreds): PodcastIndexValidation
}

/**
 * Validates Podcast Index creds with one cheap authenticated call (trending, max 1) using a
 * short-lived client built from the candidate creds. 401/403 in the error chain → Invalid;
 * anything else that throws → NetworkError. [probe] is injected so tests don't hit the network.
 *
 * The probe client is wired to the shared [PodcastIndexSharedEngineFactory], so repeated "Connect"
 * taps reuse one engine instead of leaking a fresh engine (connection pool + threads) per attempt.
 */
class DefaultPodcastIndexValidator(
    private val probe: suspend (PodcastIndexCreds) -> Unit = { creds ->
        Ktor3PodcastIndexClient(
            authKey = creds.key,
            authSecret = creds.secret,
            userAgent = BuildKonfig.USER_AGENT,
        ) {
            httpClient(PodcastIndexSharedEngineFactory) {}
        }.misc.getTrending(limit = 1, includeCategories = emptyList())
    },
) : PodcastIndexValidator {
    override suspend fun validate(creds: PodcastIndexCreds): PodcastIndexValidation =
        try {
            probe(creds)
            PodcastIndexValidation.Valid
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            // Structured concurrency: a cancellation is not a validation outcome — rethrow it
            // rather than misclassifying it as NetworkError.
            throw e
        } catch (e: Throwable) {
            classifyPodcastIndexFailure(e)
        }
}

/** Walks the throwable cause chain looking for an HTTP 401/403 signal → Invalid, else NetworkError. */
internal fun classifyPodcastIndexFailure(error: Throwable): PodcastIndexValidation {
    var t: Throwable? = error
    while (t != null) {
        val m = t.message?.lowercase().orEmpty()
        if ("401" in m || "403" in m || "unauthorized" in m || "forbidden" in m) {
            return PodcastIndexValidation.Invalid
        }
        t = t.cause
    }
    return PodcastIndexValidation.NetworkError
}

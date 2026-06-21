// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory

/**
 * App-shared HTTP engine for Podcast Index SDK clients.
 *
 * Why this exists: the SDK's `PodcastIndexClient` owns the Ktor [io.ktor.client.HttpClient] it
 * builds and is **not** `Closeable`, so every client we construct would otherwise leak its own
 * engine (connection pool + dispatcher threads). The SDK does not expose a way to hand it a
 * pre-built shared `HttpClient`; its only injection seam is `config.httpClient(engineFactory) { }`,
 * which builds the client from an [HttpClientEngineFactory]. By giving it a factory whose
 * `create()` returns a single process-lifetime engine, every SDK instance — across validator
 * probes and provider rebuilds — shares one engine instead of spawning a new one per construction.
 *
 * Safety: the SDK layers its auth, serialization, retry, logging and timeout plugins on top of this
 * engine **per instance** (via `HttpClient.config { }`), so sharing the engine does not share or
 * cross-contaminate credentials — each client still signs requests with its own key/secret, and the
 * required JSON serialization plugin is still installed. The shared engine is never closed (the SDK
 * never closes its clients), which is exactly the intended app-lifetime lifecycle.
 */
internal expect val podcastIndexSharedEngine: HttpClientEngine

/**
 * Factory wrapper around [podcastIndexSharedEngine] suitable for the SDK's
 * `config.httpClient(engineFactory) { }` DSL. `create()` always returns the same shared engine, so
 * no new engine is spun up per SDK-client construction.
 */
internal object PodcastIndexSharedEngineFactory : HttpClientEngineFactory<HttpClientEngineConfig> {
    override fun create(block: HttpClientEngineConfig.() -> Unit): HttpClientEngine = podcastIndexSharedEngine
}

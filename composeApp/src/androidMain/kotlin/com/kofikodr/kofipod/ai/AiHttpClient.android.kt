// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import com.kofikodr.kofipod.data.net.kofipodJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

internal actual fun buildAiHttpClient(): HttpClient =
    HttpClient(OkHttp) {
        install(ContentNegotiation) { json(kofipodJson) }
        // Defaults installed at the engine level: connect + socket-inactivity
        // are real ceilings (a network that goes silent for >120s is dead, not
        // just slow); request timeout is INFINITE here so a 200MB chunked
        // upload doesn't get axed mid-stream. Per-call `timeout { ... }`
        // overrides in [GeminiClient] put a wall-clock cap on inference and
        // metadata calls — see the call sites for the per-operation budget.
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
        // Intentionally NO Logging plugin — see AiHttpClient.kt KDoc.
    }

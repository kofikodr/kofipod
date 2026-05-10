// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import com.kofikodr.kofipod.data.net.kofipodJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

internal actual fun buildAiHttpClient(): HttpClient =
    HttpClient(OkHttp) {
        install(ContentNegotiation) { json(kofipodJson) }
        // Gemini 2.5 Flash routinely takes 15–30s for a transcript-length input;
        // OkHttp's 10s defaults would surface as AiError.Network mid-generation.
        // 90s request / 60s socket gives the model headroom on long episodes
        // without making the user wait forever on a hung connection.
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 90_000
            socketTimeoutMillis = 60_000
        }
        // Intentionally NO Logging plugin — see AiHttpClient.kt KDoc.
    }

// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import com.kofikodr.kofipod.data.net.kofipodJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

internal actual fun buildAiHttpClient(): HttpClient =
    HttpClient(Darwin) {
        install(ContentNegotiation) { json(kofipodJson) }
        // See AiHttpClient.android.kt — Gemini's text-summary calls regularly
        // exceed Darwin's default timeouts on a transcript-length input.
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            requestTimeoutMillis = 90_000
            socketTimeoutMillis = 60_000
        }
        // Intentionally NO Logging plugin — see AiHttpClient.kt KDoc.
    }

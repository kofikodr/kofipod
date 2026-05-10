// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

import com.kofikodr.kofipod.data.net.kofipodJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

internal actual fun buildAiHttpClient(): HttpClient =
    HttpClient(Darwin) {
        install(ContentNegotiation) { json(kofipodJson) }
        // See AiHttpClient.android.kt for the rationale on the per-call
        // timeout posture. The Darwin engine respects the same Ktor plugin
        // config; URLSession's wall-clock `timeoutInterval` is bypassed by
        // setting requestTimeoutMillis to infinite here.
        install(HttpTimeout) {
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 120_000
            requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        }
        // Intentionally NO Logging plugin — see AiHttpClient.kt KDoc.
    }

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import app.kofipod.data.net.kofipodJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

internal actual fun buildAiHttpClient(): HttpClient =
    HttpClient(Darwin) {
        install(ContentNegotiation) { json(kofipodJson) }
        // Intentionally NO Logging plugin — see AiHttpClient.kt KDoc.
    }

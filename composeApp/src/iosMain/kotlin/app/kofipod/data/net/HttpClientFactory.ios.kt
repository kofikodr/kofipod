// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

actual fun buildHttpClient(): HttpClient = HttpClient(Darwin) {
    install(ContentNegotiation) { json(kofipodJson) }
}

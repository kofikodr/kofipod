// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.net

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

expect fun buildHttpClient(): HttpClient

val kofipodJson: Json =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

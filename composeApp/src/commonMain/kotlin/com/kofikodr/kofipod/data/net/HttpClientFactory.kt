// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.net

import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json

expect fun buildHttpClient(): HttpClient

val kofipodJson: Json =
    Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = true
    }

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.data.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json

// If you add the Ktor `Logging` plugin (or any other request-inspecting plugin)
// here, audit every secret-carrying client first. AI calls to Gemini already
// route through `app.kofipod.ai.buildAiHttpClient()` precisely so they don't
// inherit any logging change made here, but other future BYOK integrations
// must do the same — never carry a credential in a URL query parameter on a
// client that has logging installed.
actual fun buildHttpClient(): HttpClient =
    HttpClient(OkHttp) {
        install(ContentNegotiation) { json(kofipodJson) }
    }

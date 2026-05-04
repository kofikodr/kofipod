// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

import io.ktor.client.HttpClient

/**
 * Builds the [HttpClient] used by every BYOK AI call. Deliberately separate from
 * the app's shared `buildHttpClient()` because Gemini and the Files API both
 * carry the user's API key as a URL query parameter (`?key=…`).
 *
 * **Do not install Ktor's `Logging` plugin on the client this factory returns**,
 * and do not route AI requests through the shared client either: a logging
 * plugin (or any future request-inspector plugin) would surface the full URL —
 * including `?key=…` — into whatever sink the plugin writes to. Plugins install
 * at construction time, so giving the AI client its own HttpClient is a
 * structural guarantee, not a convention.
 *
 * If you need a new BYOK endpoint (e.g. Files API upload, audio
 * `generateContent`), build it on top of the client this factory returns.
 */
internal expect fun buildAiHttpClient(): HttpClient

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

/**
 * Gemini models we expose in the AI Setup picker. Pro is intentionally absent because as of
 * April 2026 it moved behind a paid tier — out of scope for our free-tier BYOK flow.
 */
enum class GeminiModel(val apiId: String, val displayName: String) {
    Flash("gemini-2.5-flash", "Flash"),
    FlashLite("gemini-2.5-flash-lite", "Flash-Lite"),
    ;

    companion object {
        fun fromApiId(apiId: String): GeminiModel = entries.firstOrNull { it.apiId == apiId } ?: Flash
    }
}

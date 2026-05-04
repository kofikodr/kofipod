// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

/**
 * Returns the device's primary locale as a BCP-47 tag (e.g. `en-US`, `pt-BR`).
 *
 * Used to drive [AiPrompts.episodeSummaryPrompt] so non-English users get
 * summaries in their language. `commonMain` cannot read `java.util.Locale`
 * (Android-only) or `NSLocale` (iOS-only) directly — hence expect/actual.
 *
 * Falls back to `"en-US"` if the platform reports no language. The Gemini model
 * tolerates an unknown tag gracefully, so a sensible default is safer than
 * throwing.
 */
internal expect fun currentLocaleTag(): String

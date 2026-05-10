// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ai

/**
 * What [ChatSummariser.chat] sends as the first user turn — the source the
 * model grounds every subsequent answer in. Two variants today, both already
 * representable by Gemini's [Part] DTO (the wire shape natively supports text
 * AND fileData parts on the same `Content`).
 *
 * A future [Video] variant lands as one new subtype plus one new branch in
 * [GeminiClient.chat]; nothing else changes.
 */
sealed interface ChatContext {
    /** Transcript text fetched from the publisher; embedded directly as a text part. */
    data class Transcript(val text: String) : ChatContext

    /**
     * Already-uploaded Gemini Files API audio reference. The same URI may be
     * referenced across multiple [chat] calls within the 48h Files API TTL,
     * so reusing a Summary-side upload for a Discuss session is one cache
     * lookup away.
     */
    data class Audio(val fileUri: String, val mimeType: String) : ChatContext
}

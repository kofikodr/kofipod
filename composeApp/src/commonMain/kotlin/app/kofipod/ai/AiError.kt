// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

/**
 * Failure modes for the AI surface. Mapped to user-facing copy by the UI layer
 * (see Slice 4 — AiErrorMessage). Never expose raw HTTP bodies to the user.
 */
sealed class AiError {
    data object NoKey : AiError()

    data object KeyInvalid : AiError()

    data object RateLimited : AiError()

    data object AudioTooLong : AiError()

    data object Network : AiError()

    /** Transcript fetch returned non-2xx or threw before reaching Gemini. */
    data object TranscriptUnavailable : AiError()

    data class Unknown(val statusCode: Int? = null) : AiError()
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ai

/**
 * Domain projection of one episode's Discuss / Q&A state. Both surfaces (the
 * compact tab card and the full-screen Ask Gemini chat) consume this single
 * shape, then pick the fields they need. Keeping one state means a turn
 * landing in the DB updates both screens in lockstep without two
 * subscriptions racing.
 */
sealed interface DiscussUiState {
    /** No Gemini key configured — caller hides the entire surface. */
    data object Hidden : DiscussUiState

    /**
     * Key configured but the episode has no usable source (no transcript
     * today; in Phase 2, no transcript and no downloaded audio either).
     * The tab card explains the next step; the full screen never opens —
     * the entry button is disabled.
     */
    data object NoSource : DiscussUiState

    /**
     * Key + source ready. [messages] is the persisted chat history (oldest
     * first), [suggestions] are the four episode-specific question seeds,
     * [quickPrompts] is the static chip set, [inFlight] is true while a
     * turn is being generated (composer disables itself), [error] is the
     * most recent transient failure (cleared on next send).
     *
     * [progress] is non-null only while the **first** audio-backed send in a
     * session is uploading — drives the staged progress card with cancel.
     * Cache hits skip this entirely; subsequent turns within the same session
     * use the regular [inFlight] spinner.
     *
     * [audioTurnWarningVisible] flips on at the audio-chat-turn threshold
     * (see [DiscussRepository.AUDIO_TURN_WARNING_THRESHOLD]) — every audio
     * turn re-processes the file on Gemini's side, so long chats burn quota
     * faster than transcript chats. Banner copy lives in the UI layer.
     */
    data class Ready(
        val messages: List<DiscussMessage>,
        val suggestions: List<String>,
        val quickPrompts: List<String>,
        val inFlight: Boolean,
        val error: AiError?,
        val progress: DiscussProgress? = null,
        val audioTurnWarningVisible: Boolean = false,
    ) : DiscussUiState
}

/**
 * One persisted Q&A turn lifted to the domain layer. [citations] is empty
 * for user turns; for model turns it carries whatever the model emitted in
 * the structured response (may be empty if the model didn't cite anything).
 */
data class DiscussMessage(
    val id: String,
    val role: DiscussRole,
    val content: String,
    val citations: List<DiscussCitation>,
    val createdAtMs: Long,
)

/**
 * A timestamped pointer into the transcript that supports a model claim.
 * [label] is the pretty form ("12:34") shown verbatim; [timestampMs] is the
 * same point in the audio so the citation tap can call `player.seekTo(...)`.
 */
data class DiscussCitation(
    val label: String,
    val timestampMs: Long,
)

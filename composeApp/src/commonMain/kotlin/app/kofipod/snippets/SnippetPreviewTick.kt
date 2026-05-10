// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.snippets

/**
 * Pure helpers used by `SnippetEditorViewModel` to drive the preview playhead
 * line over the snippet waveform.
 *
 * The editor polls a wall-clock timer rather than the player's own positionMs
 * because the global player only emits position updates every 500ms — sampling
 * that directly produces a line that jumps in 500ms steps. Wall-clock
 * interpolation gives smooth motion at the poll rate.
 *
 * The helpers are pure functions so they can be unit-tested without faking the
 * `expect class KofipodPlayer`.
 */
object SnippetPreviewTick {
    /**
     * Compute where the preview playhead should sit at this tick.
     *
     * @param baseMs    Position in the episode where preview started — the user's
     *                  trim `startMs`.
     * @param elapsedMs Wall-clock milliseconds since preview started. Negative
     *                  values (clock skew) are treated as zero.
     * @param speed     Player playback speed, e.g. 1.0f or 1.5f.
     * @param endMs     Trim end. When the projected position reaches or passes
     *                  this, the result is [Result.End] so the caller can stop.
     */
    fun project(
        baseMs: Long,
        elapsedMs: Long,
        speed: Float,
        endMs: Long,
    ): Result {
        val safeElapsed = elapsedMs.coerceAtLeast(0L)
        val safeSpeed = speed.coerceAtLeast(MIN_SPEED)
        val advanced = (safeElapsed * safeSpeed).toLong()
        val projected = baseMs + advanced
        return if (projected >= endMs) Result.End(endMs) else Result.Continue(projected)
    }

    /**
     * Should we resync the wall-clock baseline against the player's authoritative
     * `positionMs` this tick? Triggers when the projected position has drifted
     * more than [DRIFT_THRESHOLD_MS] from what the player reports.
     *
     * Returning `null` means "no resync needed". Returning a value means "reset
     * the wall-clock baseline so future ticks start from this player position".
     */
    fun resyncIfDrifted(
        projectedMs: Long,
        playerPositionMs: Long,
    ): Long? {
        val drift = kotlin.math.abs(projectedMs - playerPositionMs)
        return if (drift > DRIFT_THRESHOLD_MS) playerPositionMs else null
    }

    sealed class Result {
        /** Preview should keep running; line moves to [positionMs]. */
        data class Continue(val positionMs: Long) : Result()

        /** Preview window exhausted; caller should stop playback. */
        data class End(val positionMs: Long) : Result()
    }

    /** Below this the math degenerates (zero or negative speed makes no sense). */
    private const val MIN_SPEED = 0.1f

    /**
     * Re-baseline against the player when projected and authoritative positions
     * differ by more than this. 750ms ≈ 1.5× the player's 500ms tick, large
     * enough to ignore normal jitter but small enough to catch real drift
     * (audio focus loss, user-initiated seek, speed change mid-preview).
     */
    private const val DRIFT_THRESHOLD_MS = 750L
}

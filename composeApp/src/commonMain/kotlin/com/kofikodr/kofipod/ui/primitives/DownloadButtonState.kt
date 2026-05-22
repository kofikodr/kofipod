// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.primitives

import com.kofikodr.kofipod.db.Download

/**
 * Visual state for the three "download episode" buttons (Episode Detail tertiary,
 * Podcast Detail header newest-episode CTA, Podcast Detail per-row indicator).
 *
 * All three render via [DownloadActionButton]; the only source of variation
 * across surfaces is the value carried by this type, mapped from a [Download]
 * row through [toDownloadButtonState].
 */
sealed interface DownloadButtonState {
    /** No download row exists, or the row is in an unrecognised state. Tap to enqueue. */
    data object Idle : DownloadButtonState

    /**
     * Engine accepted the job but has no progress to report yet — covers
     * `Queued`, `WaitingForWifi`, `Paused`, and `Downloading` before the first
     * `totalBytes` is known. Renders as an indeterminate sweep. Tap to cancel.
     */
    data object Pending : DownloadButtonState

    /**
     * Bytes are streaming and `totalBytes > 0`. [fraction] is the determinate
     * arc sweep, always clamped to `[0f, 1f]`. Tap to cancel.
     */
    data class InProgress(val fraction: Float) : DownloadButtonState

    /** Terminal failure. Renders the Download icon in `c.danger`; tap re-enqueues. */
    data object Failed : DownloadButtonState

    /**
     * Terminal success: the file exists at `Download.localPath`. The button
     * paints the "downloaded" affordance — currently a Trash icon — and tap
     * routes to `onDelete` so the local copy can be removed.
     */
    data object Done : DownloadButtonState
}

/**
 * Pure mapping from a SQLDelight [Download] row (or its absence) to the visual
 * [DownloadButtonState]. The determinate-vs-indeterminate boundary is
 * `totalBytes > 0`: until the engine writes `Content-Length`, `totalBytes`
 * sits at the schema default of 0 and the button shows [Pending]. Mirrors
 * the fraction calculation already used by `InProgressRow` on the Downloads
 * tab so the two surfaces never disagree about what "47%" means.
 */
fun Download?.toDownloadButtonState(): DownloadButtonState {
    if (this == null) return DownloadButtonState.Idle
    return when (state) {
        "Completed" ->
            if (!localPath.isNullOrBlank()) DownloadButtonState.Done else DownloadButtonState.Pending
        "Failed" -> DownloadButtonState.Failed
        "Queued", "WaitingForWifi" -> DownloadButtonState.Pending
        // "Paused" is written by DownloadRepository.cancel() — i.e. the user
        // explicitly tapped Cancel. Once cancelled, the engine no longer emits
        // any more events for this row, so leaving the button in Pending would
        // strand it on the spinning-arc visual forever. Return Idle so the user
        // sees the Download icon again and can re-enqueue.
        "Paused" -> DownloadButtonState.Idle
        "Downloading" ->
            if (totalBytes > 0L) {
                val raw = downloadedBytes.toFloat() / totalBytes.toFloat()
                DownloadButtonState.InProgress(raw.coerceIn(0f, 1f))
            } else {
                DownloadButtonState.Pending
            }
        else -> DownloadButtonState.Idle
    }
}

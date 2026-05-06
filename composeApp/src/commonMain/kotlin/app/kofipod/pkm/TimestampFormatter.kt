// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

/**
 * Formats milliseconds as `MM:SS` (under an hour) or `H:MM:SS` (one hour or
 * more). Used in body text, e.g. "Listen at 12:34". Always rounds down. The
 * hour field is not zero-padded (a 10-hour episode renders as `10:00:00`).
 *
 * Negative input is clamped to zero so a transient pre-prepared Media3 player
 * position cannot leak `00:-1` into a shared markdown body.
 */
fun formatHms(ms: Long): String {
    val totalSeconds = (if (ms < 0L) 0L else ms) / 1_000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

/**
 * Formats milliseconds as `MM:SS` (under an hour) or `H:MM:SS` (one hour or
 * more). Used in body text, e.g. "Listen at 12:34". Always rounds down.
 */
fun formatHms(ms: Long): String {
    val totalSeconds = ms / 1_000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    }
}

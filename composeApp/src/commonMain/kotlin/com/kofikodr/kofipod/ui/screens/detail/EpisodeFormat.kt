// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

internal fun formatDate(epochMs: Long): String {
    val ld =
        Instant.fromEpochMilliseconds(epochMs)
            .toLocalDateTime(TimeZone.currentSystemDefault()).date
    val months = arrayOf("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC")
    return "${months[ld.monthNumber - 1]} ${ld.dayOfMonth.toString().padStart(2, '0')}"
}

internal fun formatDuration(sec: Int): String {
    val h = sec / 3600
    val m = (sec % 3600) / 60
    return if (h > 0) "${h}h ${m.toString().padStart(2, '0')}m" else "${m}m"
}

internal fun formatMb(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return "${mb.toInt()} MB"
}

/**
 * Renders a chunked-upload progress label: "12 / 180 MB". The total uses the
 * existing [formatMb] format so the eventual "180 MB" label matches the size
 * shown elsewhere on the screen byte-for-byte. Uploaded count rounds down so
 * the displayed numerator never overshoots the denominator mid-upload.
 */
internal fun formatMbProgress(
    uploadedBytes: Long,
    totalBytes: Long,
): String {
    val uploadedMb = (uploadedBytes / (1024.0 * 1024.0)).toInt()
    return "$uploadedMb / ${formatMb(totalBytes)}"
}

internal fun episodeMetaLine(
    publishedAt: Long,
    durationSec: Int,
    fileSizeBytes: Long,
): String {
    val parts = mutableListOf<String>()
    if (publishedAt > 0) parts += formatDate(publishedAt)
    if (durationSec > 0) parts += formatDuration(durationSec)
    if (fileSizeBytes > 0) parts += formatMb(fileSizeBytes)
    return if (parts.isEmpty()) "—" else parts.joinToString("  ·  ")
}

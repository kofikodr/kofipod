// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.bookmarks

/**
 * Formats a millisecond offset into a `H:MM:SS` (or `M:SS` when shorter than an hour)
 * timestamp. Used by the bookmark composer sheet, the per-episode Saved section,
 * and the global Bookmarks list — keep one source of truth so the three surfaces
 * never drift on padding or separator style.
 */
fun formatBookmarkTimestamp(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) {
        "$h:${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}"
    } else {
        "$m:${s.toString().padStart(2, '0')}"
    }
}

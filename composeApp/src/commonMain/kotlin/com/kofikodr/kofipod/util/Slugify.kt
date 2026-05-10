// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.util

/**
 * Derives a URL-safe slug from a user-supplied name. Lowercase, non-alphanumeric → '-',
 * leading/trailing dashes trimmed. If [existingIds] would collide, appends a `-2`, `-3`, …
 * suffix until unique. Empty / all-symbol names fall back to `"list"`.
 */
fun slugifyName(
    name: String,
    existingIds: Set<String>,
): String {
    val base =
        name
            .lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .trim('-')
            .ifBlank { "list" }
    if (base !in existingIds) return base
    var n = 2
    while ("$base-$n" in existingIds) n++
    return "$base-$n"
}

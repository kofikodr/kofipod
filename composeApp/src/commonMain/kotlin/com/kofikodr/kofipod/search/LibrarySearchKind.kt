// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.search

/**
 * Centralised mapping between the SQL `kind` column literal stored in the FTS5
 * `LibrarySearchIndex` table and the typed value used in Kotlin. Keep the
 * [wire] strings stable — they live inside SQL triggers, so changing them
 * means writing another migration.
 */
enum class LibrarySearchKind(val wire: String) {
    Bookmark("bookmark"),
    Summary("summary"),
    Transcript("transcript"),
    ;

    companion object {
        fun fromWire(value: String): LibrarySearchKind? = entries.firstOrNull { it.wire == value }
    }
}

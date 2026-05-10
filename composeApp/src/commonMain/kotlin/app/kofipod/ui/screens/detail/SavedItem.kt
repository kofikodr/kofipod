// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail

import app.kofipod.bookmarks.Bookmark
import app.kofipod.snippets.Snippet

/**
 * Heterogeneous row in the per-episode "Saved" section. Bookmarks (Slice 1) and
 * snippets (Slice 3) coexist in a single newest-first list, so the UI iterates
 * over `List<SavedItem>` and switches on the variant when rendering.
 *
 * Sorted by [createdAtMs] in the ViewModel — the only field both variants share.
 */
sealed interface SavedItem {
    val createdAtMs: Long

    data class BookmarkItem(val bookmark: Bookmark) : SavedItem {
        override val createdAtMs: Long get() = bookmark.createdAtMs
    }

    data class SnippetItem(val snippet: Snippet, val sizeBytes: Long) : SavedItem {
        override val createdAtMs: Long get() = snippet.createdAtMs
    }
}

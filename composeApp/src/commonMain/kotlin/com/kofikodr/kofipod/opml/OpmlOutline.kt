// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.opml

/**
 * Parsed OPML 2.0 document, normalised to the shape Kofipod cares about: a tree of
 * folder containers and RSS feed leaves. Anything else (links, includes, non-rss
 * outlines) is ignored at parse time so the import path doesn't have to defensively
 * filter again.
 */
data class OpmlDocument(
    val title: String?,
    val outlines: List<OpmlOutline>,
)

sealed interface OpmlOutline {
    /** A nested container (`<outline>` without `xmlUrl`). Becomes a `PodcastList`. */
    data class Folder(
        val name: String,
        val children: List<OpmlOutline>,
    ) : OpmlOutline

    /** An RSS feed entry (`<outline xmlUrl="…">`). Resolved against Podcast Index on import. */
    data class Feed(
        val title: String,
        val xmlUrl: String,
    ) : OpmlOutline
}

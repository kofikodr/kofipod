// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.opml

/**
 * Pure OPML 2.0 emitter. Folders nest inside `<body>`; flat entries appear at the same
 * level. Only the standard `text`, `title`, `type`, and `xmlUrl` attributes are written —
 * per-podcast settings (auto-download, notify) are intentionally not round-tripped because
 * OPML has no portable attribute for them and they're device-specific anyway.
 */
object OpmlSerializer {
    fun serialize(
        title: String,
        folders: List<ExportFolder>,
        unfiled: List<ExportFeed>,
        generatedAtIso: String,
    ): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<opml version=\"2.0\">\n")
            append("  <head>\n")
            append("    <title>").append(escape(title)).append("</title>\n")
            append("    <dateCreated>").append(escape(generatedAtIso)).append("</dateCreated>\n")
            append("  </head>\n")
            append("  <body>\n")
            for (folder in folders) {
                appendFolder(folder, indent = "    ")
            }
            for (feed in unfiled) {
                appendFeed(feed, indent = "    ")
            }
            append("  </body>\n")
            append("</opml>\n")
        }

    private fun StringBuilder.appendFolder(
        folder: ExportFolder,
        indent: String,
    ) {
        append(indent)
            .append("<outline text=\"")
            .append(escape(folder.name))
            .append("\" title=\"")
            .append(escape(folder.name))
            .append("\">\n")
        for (feed in folder.feeds) {
            appendFeed(feed, indent = "$indent  ")
        }
        append(indent).append("</outline>\n")
    }

    private fun StringBuilder.appendFeed(
        feed: ExportFeed,
        indent: String,
    ) {
        append(indent)
            .append("<outline type=\"rss\" text=\"")
            .append(escape(feed.title))
            .append("\" title=\"")
            .append(escape(feed.title))
            .append("\" xmlUrl=\"")
            .append(escape(feed.xmlUrl))
            .append("\"/>\n")
    }

    private fun escape(value: String): String =
        buildString(value.length) {
            for (ch in value) {
                when (ch) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(ch)
                }
            }
        }
}

data class ExportFolder(val name: String, val feeds: List<ExportFeed>)

data class ExportFeed(val title: String, val xmlUrl: String)

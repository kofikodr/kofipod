// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import app.kofipod.ai.AiSummary
import app.kofipod.bookmarks.Bookmark
import app.kofipod.db.Episode
import app.kofipod.db.Podcast
import app.kofipod.snippets.Snippet
import kotlinx.datetime.Instant

/**
 * Pure-Kotlin implementation. Stateless and side-effect free — safe to expose
 * as a Koin singleton. Filename slug uses [slugify] with a 24-char cap per
 * segment and a 6-char short-id suffix sourced from the artifact id.
 */
class MarkdownFormatterImpl : MarkdownFormatter {
    override fun formatSnippet(
        snippet: Snippet,
        episode: Episode,
        podcast: Podcast,
    ): MarkdownDocument {
        val title = snippet.title?.takeIf { it.isNotBlank() } ?: episode.title
        val frontmatter =
            listOf(
                "kind" to "snippet",
                "podcast" to podcast.title,
                "episode" to episode.title,
                "episodeUrl" to episode.enclosureUrl,
                "timestampMs" to snippet.startMs.toString(),
                // Use Snippet.durationMs (coerceAtLeast(0L)) so a degenerate row with
                // endMs < startMs cannot leak a negative integer into Slice 6 destination
                // adapters that parse the YAML.
                "durationMs" to snippet.durationMs.toString(),
                "createdAt" to isoFromEpochMs(snippet.createdAtMs),
                "kofipodId" to snippet.id,
            )
        val body =
            buildString {
                append("## ").append(title).append("\n\n")
                snippet.captionOverride?.takeIf { it.isNotBlank() }?.let {
                    append("> ").append(it).append("\n\n")
                }
                append("Listen at ").append(formatHms(snippet.startMs))
                    .append(" — [").append(episode.title).append("](").append(episode.enclosureUrl).append(")")
            }
        return MarkdownDocument(
            frontmatter = frontmatter,
            body = body,
            filename = buildFilename(podcast.title, title, "snippet", snippet.id),
        )
    }

    override fun formatBookmark(
        bookmark: Bookmark,
        episode: Episode,
        podcast: Podcast,
    ): MarkdownDocument {
        val frontmatter =
            listOf(
                "kind" to "bookmark",
                "podcast" to podcast.title,
                "episode" to episode.title,
                "episodeUrl" to episode.enclosureUrl,
                "timestampMs" to bookmark.timestampMs.toString(),
                "createdAt" to isoFromEpochMs(bookmark.createdAtMs),
                "kofipodId" to bookmark.id,
            )
        val body =
            buildString {
                bookmark.note?.takeIf { it.isNotBlank() }?.let {
                    append(it).append("\n\n")
                }
                append("Listen at ").append(formatHms(bookmark.timestampMs))
                    .append(" — [").append(episode.title).append("](").append(episode.enclosureUrl).append(")")
            }
        return MarkdownDocument(
            frontmatter = frontmatter,
            body = body,
            filename = buildFilename(podcast.title, episode.title, "bookmark", bookmark.id),
        )
    }

    override fun formatAiSummary(
        summary: AiSummary,
        episode: Episode,
        podcast: Podcast,
    ): MarkdownDocument {
        val frontmatter =
            listOf(
                "kind" to "summary",
                "podcast" to podcast.title,
                "episode" to episode.title,
                "episodeUrl" to episode.enclosureUrl,
                "createdAt" to isoFromEpochMs(summary.generatedAtMs),
                "kofipodId" to "summary-${episode.id}",
            )
        val body =
            buildString {
                summary.summary.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
                if (summary.people.isNotEmpty()) {
                    append("## People\n\n")
                    for (p in summary.people) {
                        append("- ").append(p.name)
                        if (p.subtitle.isNotBlank()) append(" — ").append(p.subtitle)
                        append("\n")
                    }
                    append("\n")
                }
                if (summary.things.isNotEmpty()) {
                    append("## Things\n\n")
                    for (t in summary.things) {
                        append("- ").append(t.name)
                        if (t.subtitle.isNotBlank()) append(" — ").append(t.subtitle)
                        append("\n")
                    }
                    append("\n")
                }
                if (summary.links.isNotEmpty()) {
                    append("## Links\n\n")
                    for (l in summary.links) {
                        append("- [").append(l.label).append("](").append(l.url).append(")\n")
                    }
                    append("\n")
                }
            }
        return MarkdownDocument(
            frontmatter = frontmatter,
            body = body,
            filename = buildFilename(podcast.title, episode.title, "summary", episode.id),
        )
    }

    private fun buildFilename(
        podcastTitle: String,
        secondaryTitle: String,
        kind: String,
        idForSuffix: String,
    ): String {
        val pSlug = slugify(podcastTitle, maxLen = SLUG_SEGMENT_MAX)
        val sSlug = slugify(secondaryTitle, maxLen = SLUG_SEGMENT_MAX)
        val shortId = idForSuffix.takeLast(SHORT_ID_LEN)
        return "$pSlug-$sSlug-$kind-$shortId.md"
    }

    private fun isoFromEpochMs(ms: Long): String = Instant.fromEpochMilliseconds(ms).toString()

    private companion object {
        private const val SLUG_SEGMENT_MAX = 24
        private const val SHORT_ID_LEN = 6
    }
}

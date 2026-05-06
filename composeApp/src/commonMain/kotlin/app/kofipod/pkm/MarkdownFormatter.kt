// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import app.kofipod.ai.AiSummary
import app.kofipod.bookmarks.Bookmark
import app.kofipod.db.Episode
import app.kofipod.db.Podcast
import app.kofipod.snippets.Snippet

/**
 * Pure markdown formatter — no I/O, no clock, no repos. Caller resolves the
 * domain types and passes them in. Returns a [MarkdownDocument] ready to copy
 * to clipboard or write to a `.md` file.
 *
 * **Frontmatter contract** (key order is fixed and verified by tests, because
 * Slice 6 destination adapters parse this output):
 *   - Snippet: kind, podcast, episode, episodeUrl, timestampMs, durationMs, createdAt, kofipodId
 *   - Bookmark: kind, podcast, episode, episodeUrl, timestampMs, createdAt, kofipodId
 *   - AiSummary: kind, podcast, episode, episodeUrl, createdAt, kofipodId
 *
 * `kofipodId` is the artifact's own id for snippets and bookmarks. AI summaries
 * have no artifact-level id — `EpisodeAiSummary` is keyed by `episodeId` — so
 * the synthetic form `"summary-<episodeId>"` is used. Slice 6 destination
 * adapters that key off `kofipodId` for upsert MUST tolerate this prefixed
 * shape.
 */
interface MarkdownFormatter {
    fun formatSnippet(
        snippet: Snippet,
        episode: Episode,
        podcast: Podcast,
    ): MarkdownDocument

    fun formatBookmark(
        bookmark: Bookmark,
        episode: Episode,
        podcast: Podcast,
    ): MarkdownDocument

    fun formatAiSummary(
        summary: AiSummary,
        episode: Episode,
        podcast: Podcast,
    ): MarkdownDocument
}

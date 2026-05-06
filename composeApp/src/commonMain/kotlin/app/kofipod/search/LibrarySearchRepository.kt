// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.search

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn

/**
 * Pro Library FTS5 search over Bookmark notes, AI summaries, and cached transcripts.
 *
 * Goes through `SqlDriver.executeQuery` directly because SQLDelight 2.0.2's
 * default SQLite 3.18 dialect cannot parse FTS5 `ORDER BY rank` + `snippet(...)`.
 * See `LibrarySearchIndex.sq`'s comment block for the canonical SQL.
 *
 * Returns a one-shot `Flow` per call (not reactive) — the search use-case is
 * "user types, I show results", not "results auto-refresh while user reads".
 * If a future feature needs reactivity, wire `driver.addListener(...)` here.
 */
class LibrarySearchRepository(
    private val driver: SqlDriver,
) {
    fun search(
        rawQuery: String,
        kind: LibrarySearchKind? = null,
    ): Flow<List<LibrarySearchResult>> {
        val expression = LibrarySearchQuery.toFtsExpression(rawQuery) ?: return flowOf(emptyList())
        return flow { emit(executeSearch(expression, kind)) }.flowOn(Dispatchers.Default)
    }

    private fun executeSearch(
        expression: String,
        kind: LibrarySearchKind?,
    ): List<LibrarySearchResult> {
        val sql = if (kind == null) SQL_SEARCH else SQL_SEARCH_BY_KIND
        val parameterCount = if (kind == null) 1 else 2
        val rows = mutableListOf<LibrarySearchResult>()
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor: SqlCursor ->
                while (cursor.next().value) {
                    val typed = LibrarySearchKind.fromWire(cursor.getString(0)!!) ?: continue
                    val itemId = cursor.getString(1)!!
                    val episodeId = cursor.getString(2)!!
                    val timestampMs = cursor.getLong(3) ?: 0L
                    val excerpt = cursor.getString(4) ?: ""
                    val episodeTitle = cursor.getString(5)!!
                    val podcastId = cursor.getString(6)!!
                    val podcastTitle = cursor.getString(7)!!
                    val artworkUrl = cursor.getString(8) ?: ""
                    rows +=
                        when (typed) {
                            LibrarySearchKind.Bookmark ->
                                LibrarySearchResult.BookmarkMatch(
                                    bookmarkId = itemId,
                                    timestampMs = timestampMs,
                                    episodeId = episodeId,
                                    episodeTitle = episodeTitle,
                                    podcastId = podcastId,
                                    podcastTitle = podcastTitle,
                                    artworkUrl = artworkUrl,
                                    excerpt = excerpt,
                                )
                            LibrarySearchKind.Summary ->
                                LibrarySearchResult.SummaryMatch(
                                    episodeId = episodeId,
                                    episodeTitle = episodeTitle,
                                    podcastId = podcastId,
                                    podcastTitle = podcastTitle,
                                    artworkUrl = artworkUrl,
                                    excerpt = excerpt,
                                )
                            LibrarySearchKind.Transcript ->
                                LibrarySearchResult.TranscriptMatch(
                                    episodeId = episodeId,
                                    episodeTitle = episodeTitle,
                                    podcastId = podcastId,
                                    podcastTitle = podcastTitle,
                                    artworkUrl = artworkUrl,
                                    excerpt = excerpt,
                                )
                        }
                }
                QueryResult.Value(Unit)
            },
            parameters = parameterCount,
            binders = {
                bindString(0, expression)
                if (kind != null) bindString(1, kind.wire)
            },
        )
        return rows
    }

    private companion object {
        /**
         * Mixed-kind FTS5 search across all indexed content. Returns up to 100 rows
         * ordered by relevance rank. The `snippet(...)` call generates a short excerpt
         * with `<<…>>` match markers around column index 4 (the `text` column).
         */
        private const val SQL_SEARCH = """
            SELECT fts.kind, fts.itemId, fts.episodeId, fts.timestampMs,
                   snippet(LibrarySearchIndex, 4, '<<', '>>', '…', 12) AS excerpt,
                   e.title AS episodeTitle, p.id AS podcastId,
                   p.title AS podcastTitle, p.artworkUrl AS artworkUrl
            FROM LibrarySearchIndex fts
            INNER JOIN Episode e ON e.id = fts.episodeId
            INNER JOIN Podcast p ON p.id = e.podcastId
            WHERE LibrarySearchIndex MATCH ?
            ORDER BY rank
            LIMIT 100
        """

        /**
         * Same as [SQL_SEARCH] but restricted to a single [LibrarySearchKind] bucket.
         *
         * At very large data volumes (>100 mixed-kind FTS hits before kind filter), this can
         * truncate kind-filtered results because FTS applies LIMIT before the AND fts.kind
         * post-filter. Acceptable for slice 2's expected data volume; if it bites in
         * production, make `kind` INDEXED and push it into the MATCH expression.
         */
        private const val SQL_SEARCH_BY_KIND = """
            SELECT fts.kind, fts.itemId, fts.episodeId, fts.timestampMs,
                   snippet(LibrarySearchIndex, 4, '<<', '>>', '…', 12) AS excerpt,
                   e.title AS episodeTitle, p.id AS podcastId,
                   p.title AS podcastTitle, p.artworkUrl AS artworkUrl
            FROM LibrarySearchIndex fts
            INNER JOIN Episode e ON e.id = fts.episodeId
            INNER JOIN Podcast p ON p.id = e.podcastId
            WHERE LibrarySearchIndex MATCH ?
              AND fts.kind = ?
            ORDER BY rank
            LIMIT 100
        """
    }
}

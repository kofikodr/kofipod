// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.search

/**
 * Turn raw user input from the Library search bar into an FTS5 expression.
 *
 * Rules:
 *   - Blank → `null` (caller emits an empty result list without hitting SQLite).
 *   - Each whitespace-delimited token is wrapped as an FTS5 string literal
 *     (double-quoted, with embedded `"` doubled) and given a `*` prefix
 *     suffix so partial matches show up as the user types.
 *   - Multiple tokens are space-joined → FTS5 implicit AND.
 *
 * Why not strip punctuation: FTS5's `unicode61` tokenizer already handles
 * apostrophes / dashes correctly inside quoted literals. Stripping them
 * would lose phrases like `"it's"` or `"co-pilot"`.
 */
object LibrarySearchQuery {
    fun toFtsExpression(raw: String): String? {
        val tokens = raw.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(separator = " ") { token ->
            val escaped = token.replace("\"", "\"\"")
            "\"$escaped\"*"
        }
    }

    private val WHITESPACE = Regex("\\s+")
}

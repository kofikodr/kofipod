// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

/**
 * Renders to a `.md` blob with optional YAML frontmatter. Frontmatter key
 * order is preserved exactly as supplied (insertion order). Values are quoted
 * and escaped against `"` and `\`.
 *
 * Body is appended verbatim; a trailing newline is appended if the body does
 * not already end with one. Empty frontmatter omits the `---` block entirely.
 *
 * @property frontmatter ordered key/value pairs; empty list = no frontmatter block.
 * @property body raw markdown body. Caller is responsible for any markdown
 *   escaping inside the body.
 * @property filename safe filename including `.md` extension. Used by file sinks.
 */
data class MarkdownDocument(
    val frontmatter: List<Pair<String, String>>,
    val body: String,
    val filename: String,
) {
    fun render(): String =
        buildString {
            if (frontmatter.isNotEmpty()) {
                append("---\n")
                for ((key, value) in frontmatter) {
                    append(key).append(": \"").append(escape(value)).append("\"\n")
                }
                append("---\n\n")
            }
            append(body)
            if (!body.endsWith("\n")) append('\n')
        }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}

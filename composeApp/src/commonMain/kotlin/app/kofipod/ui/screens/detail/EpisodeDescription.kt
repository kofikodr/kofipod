// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

/**
 * Renders an RSS episode description (typically a small subset of HTML) as an
 * [AnnotatedString] suitable for `Text(annotatedString)`. We accept the most common
 * shapes podcasters publish — `<p>`, `<br>`, `<a>`, inline emphasis, lists — and
 * silently drop everything else. The output preserves paragraph breaks and turns
 * `<a href>` anchors plus bare http(s) URLs into [LinkAnnotation.Url] spans.
 *
 * Why a hand-rolled parser instead of `kotlinx.html` / a real HTML library:
 * this only ever consumes ~few-KB feed-supplied snippets, doesn't need to be
 * robust against pathological inputs, and we want it to compile for iOS where
 * the JVM-only HTML libs aren't available.
 *
 * Correctness invariants pinned by [EpisodeDescriptionTest]:
 *  - Literal `<` characters not followed by a letter (e.g. `"speed is <3x"`)
 *    survive — the previous `<[^>]+>` regex ate everything up to the next `>`.
 *  - `&amp;` decode happens last so `&amp;lt;` round-trips to `&lt;`, not `<`.
 *  - Anchor inner text falls back to the URL when blank (otherwise the link is
 *    invisible).
 */
internal fun renderDescription(raw: String): AnnotatedString {
    if (raw.isBlank()) return AnnotatedString("")

    // Step 1: rewrite block-level tags into newlines. Done before anchor extraction
    // so the resulting text already has paragraph structure baked in.
    val normalized =
        raw
            .replace(BR_TAG, "\n")
            .replace(P_CLOSE, "\n\n")
            .replace(P_OPEN, "")
            .replace(LI_CLOSE, "\n")
            .replace(LI_OPEN, "• ")

    val built =
        buildAnnotatedString {
            var cursor = 0
            for (match in ANCHOR_TAG.findAll(normalized)) {
                appendInline(normalized.substring(cursor, match.range.first))
                val href = decodeEntities(match.groupValues[1]).trim()
                val innerRaw = match.groupValues[2]
                val innerText =
                    decodeEntities(stripTags(innerRaw))
                        .replace(INLINE_WHITESPACE, " ")
                        .trim()
                        .ifBlank { href }
                withLink(LinkAnnotation.Url(href)) {
                    withStyle(LINK_SPAN_STYLE) { append(innerText) }
                }
                cursor = match.range.last + 1
            }
            appendInline(normalized.substring(cursor))
        }
    return built.trimWhitespace()
}

/**
 * AnnotatedString-aware trim — uses [AnnotatedString.subSequence] so any link spans
 * that survive the trim retain correct offsets.
 */
private fun AnnotatedString.trimWhitespace(): AnnotatedString {
    if (text.isEmpty()) return this
    var start = 0
    while (start < text.length && text[start].isWhitespace()) start++
    var end = text.length
    while (end > start && text[end - 1].isWhitespace()) end--
    if (start == 0 && end == text.length) return this
    if (start >= end) return AnnotatedString("")
    return subSequence(start, end)
}

/**
 * Append a text segment that may contain stray inline tags, entities, and bare URLs.
 * Strips any tags that survived the block-level pass (only matches well-formed tags
 * starting with a letter), decodes entities, collapses inline whitespace runs,
 * collapses 3+ newlines to a single paragraph break, and linkifies bare URLs.
 */
private fun AnnotatedString.Builder.appendInline(segment: String) {
    if (segment.isEmpty()) return
    val cleaned =
        decodeEntities(stripTags(segment))
            .replace(INLINE_WHITESPACE, " ")
            .replace(NEWLINE_RUNS, "\n\n")

    var cursor = 0
    for (match in BARE_URL.findAll(cleaned)) {
        append(cleaned.substring(cursor, match.range.first))
        val url = match.value.trimEnd('.', ',', ';', ':', ')', ']', '!', '?')
        withLink(LinkAnnotation.Url(url)) {
            withStyle(LINK_SPAN_STYLE) { append(url) }
        }
        // If we trimmed trailing punctuation off the matched URL, append it as plain text.
        val trimmedTail = match.value.removePrefix(url)
        if (trimmedTail.isNotEmpty()) append(trimmedTail)
        cursor = match.range.last + 1
    }
    append(cleaned.substring(cursor))
}

private fun stripTags(s: String): String = s.replace(STRICT_TAG, "")

/**
 * Decodes the named entities seen in real-world podcast feeds. `&amp;` is decoded last
 * so an input like `&amp;lt;` resolves to the visible string `&lt;` rather than `<`.
 */
private fun decodeEntities(s: String): String =
    s
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&hellip;", "…")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace("&amp;", "&")

private val LINK_SPAN_STYLE = SpanStyle(textDecoration = TextDecoration.Underline)

// Tag-shape match must start with a letter (or `/letter`); this is what protects
// literal `<3` / `<EOF>` etc. from being eaten as if they were HTML tags.
private val STRICT_TAG = Regex("</?[a-zA-Z][^<>]*>")

private val BR_TAG = Regex("<br\\s*/?\\s*>", RegexOption.IGNORE_CASE)
private val P_OPEN = Regex("<p\\b[^>]*>", RegexOption.IGNORE_CASE)
private val P_CLOSE = Regex("</p\\s*>", RegexOption.IGNORE_CASE)
private val LI_OPEN = Regex("<li\\b[^>]*>", RegexOption.IGNORE_CASE)
private val LI_CLOSE = Regex("</li\\s*>", RegexOption.IGNORE_CASE)

private val ANCHOR_TAG =
    Regex(
        "<a\\b[^>]*\\bhref\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )

// Inline whitespace only — preserve newlines so paragraph breaks survive the squash.
private val INLINE_WHITESPACE = Regex("[ \\t\\u00A0]+")
private val NEWLINE_RUNS = Regex("\\n{3,}")

private val BARE_URL = Regex("https?://[^\\s<>\"'()\\[\\]]+")

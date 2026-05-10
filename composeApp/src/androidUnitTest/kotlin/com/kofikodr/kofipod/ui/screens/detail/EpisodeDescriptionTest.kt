// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [renderDescription]'s parsing contract. The function is the only place podcast
 * description HTML gets normalized for display, so each behavioural rule deserves a
 * dedicated assertion — silent regressions here corrupt every episode body in the app.
 */
class EpisodeDescriptionTest {
    // -------------------------------------------------------------------------
    // Bug-fix regressions: things the old `<[^>]+>` regex got wrong.
    // -------------------------------------------------------------------------

    @Test
    fun preservesLiteralLessThanFollowedByDigit() {
        // The pre-Slice-4 implementation matched `<[^>]+>` and would eat
        // "<3x faster than" up to the next `>` on the page (often hundreds of
        // chars later). The strict tag regex requires a letter after `<`, so
        // `<3` is left alone.
        val out = renderDescription("Playback is <3x faster than realtime.")
        assertEquals("Playback is <3x faster than realtime.", out.text)
    }

    @Test
    fun preservesLiteralLessThanWhenUsedAsHeart() {
        val out = renderDescription("I <3 podcasts <about> craft.")
        // "<3 podcasts " survives. "<about>" looks like a tag (letter after `<`)
        // and gets stripped; the resulting double space is squashed back to one
        // by the inline-whitespace pass, which is the right call for prose.
        assertEquals("I <3 podcasts craft.", out.text)
    }

    @Test
    fun decodesAmpersandEntityLast_soDoubleEncodedEntitiesRoundTrip() {
        // `&amp;lt;` is what you'd write to *display* the literal text "&lt;".
        // If we naively decoded `&amp;` first, it would become `&lt;` and then
        // `<`, silently corrupting the visible text.
        val out = renderDescription("Use &amp;lt; for less-than.")
        assertEquals("Use &lt; for less-than.", out.text)
    }

    // -------------------------------------------------------------------------
    // Block-level structure: paragraphs and breaks.
    // -------------------------------------------------------------------------

    @Test
    fun rewritesParagraphTagsToDoubleNewlines() {
        val out = renderDescription("<p>First paragraph.</p><p>Second.</p>")
        assertEquals("First paragraph.\n\nSecond.", out.text)
    }

    @Test
    fun rewritesBrTagsToSingleNewline_acrossSpacingVariants() {
        val out = renderDescription("Line one<br>Line two<br/>Line three<br />Line four")
        assertEquals("Line one\nLine two\nLine three\nLine four", out.text)
    }

    @Test
    fun collapsesThreeOrMoreNewlinesIntoParagraphBreak() {
        // </p><br><p> would naively yield \n\n + \n + \n\n = 5 newlines. The
        // collapse keeps the visual rhythm consistent with a single blank line.
        val out = renderDescription("<p>One</p><br><br><p>Two</p>")
        assertEquals("One\n\nTwo", out.text)
    }

    @Test
    fun trimsLeadingAndTrailingWhitespaceFromOutput() {
        // A trailing </p> would otherwise leave "\n\n" at the end of the text.
        val out = renderDescription("   <p>Body.</p>   ")
        assertEquals("Body.", out.text)
    }

    // -------------------------------------------------------------------------
    // Entities and tag stripping.
    // -------------------------------------------------------------------------

    @Test
    fun stripsInlineEmphasisTagsButKeepsTheirText() {
        val out = renderDescription("<strong>Bold</strong> and <em>italic</em> together.")
        assertEquals("Bold and italic together.", out.text)
    }

    @Test
    fun decodesCommonNamedEntities() {
        // Note: `&nbsp;` decodes to a regular space and the surrounding inline
        // whitespace then collapses to a single space — sane for body text.
        val out =
            renderDescription(
                "Tom &amp; Jerry &nbsp; — &quot;hi&quot; isn&#39;t the best&hellip;",
            )
        assertEquals("Tom & Jerry — \"hi\" isn't the best…", out.text)
    }

    @Test
    fun returnsEmptyAnnotatedStringForBlankInput() {
        assertEquals("", renderDescription("").text)
        assertEquals("", renderDescription("   \n\t  ").text)
    }

    @Test
    fun returnsEmptyAnnotatedStringForHtmlOnlyInput() {
        // RSS feeds occasionally emit "<p></p>" or "<br/>" alone. The screen
        // gates the description block on the rendered text, so the parser must
        // produce a blank result for tag-only inputs to keep the screen from
        // showing an empty paragraph.
        assertEquals("", renderDescription("<p></p>").text)
        assertEquals("", renderDescription("<br/><br/>").text)
        assertEquals("", renderDescription("<p>   </p>").text)
    }

    // -------------------------------------------------------------------------
    // Anchor extraction → LinkAnnotation.Url.
    // -------------------------------------------------------------------------

    @Test
    fun extractsAnchorTagsAsUrlLinkAnnotations() {
        val raw = """Visit <a href="https://kofipod.app">our site</a> for more."""
        val out = renderDescription(raw)
        assertEquals("Visit our site for more.", out.text)

        val links = out.urlLinks()
        assertEquals(1, links.size, "Exactly one link span expected")
        val (range, url) = links.single()
        assertEquals("https://kofipod.app", url)
        assertEquals("our site", out.text.substring(range))
    }

    @Test
    fun anchorWithBlankInnerText_fallsBackToHref_soLinkIsVisible() {
        // Some feeds emit `<a href="..."></a>` (e.g. wrapping an <img> we've stripped).
        // Without the fallback the link would have zero width and be untappable.
        val raw = """Click <a href="https://example.com"></a> here."""
        val out = renderDescription(raw)
        assertEquals("Click https://example.com here.", out.text)

        val links = out.urlLinks()
        assertEquals(1, links.size)
        assertEquals("https://example.com", links.single().second)
    }

    @Test
    fun anchorWithNestedFormatting_keepsTextStripsTags() {
        val raw = """See <a href="https://x.test"><strong>this thing</strong></a>."""
        val out = renderDescription(raw)
        assertEquals("See this thing.", out.text)
        assertEquals(listOf("https://x.test"), out.urlLinks().map { it.second })
    }

    // -------------------------------------------------------------------------
    // Bare URL linkification.
    // -------------------------------------------------------------------------

    @Test
    fun linkifiesBareHttpsUrl() {
        val out = renderDescription("Read https://kofipod.app/privacy for details.")
        assertEquals("Read https://kofipod.app/privacy for details.", out.text)
        val (range, url) = out.urlLinks().single()
        assertEquals("https://kofipod.app/privacy", url)
        assertEquals("https://kofipod.app/privacy", out.text.substring(range))
    }

    @Test
    fun trimsTrailingPunctuationFromLinkifiedUrl() {
        // Common in prose: "see https://example.com." — we must not include the
        // period in the URL or the link breaks.
        val out = renderDescription("See https://example.com. Thanks!")
        val (_, url) = out.urlLinks().single()
        assertEquals("https://example.com", url)
        assertTrue(
            out.text.contains("https://example.com."),
            "Text should still display the period after the URL",
        )
    }

    @Test
    fun doesNotDoubleLinkifyAnchorContentsThatLookLikeUrls() {
        // The anchor's inner text "https://x.test" must produce only ONE link
        // (from the anchor itself) — not also a bare-URL match on the inner.
        val raw = """<a href="https://x.test">https://x.test</a>"""
        val out = renderDescription(raw)
        val links = out.urlLinks()
        assertEquals(1, links.size, "Anchor with URL inner must not produce a second bare link")
    }
}

/**
 * Returns each [LinkAnnotation.Url] in this string as `(IntRange, url)` pairs.
 * The Compose API exposes link spans via [AnnotatedString.getLinkAnnotations]; we
 * narrow to URL links since that is all this renderer emits today.
 */
private fun AnnotatedString.urlLinks(): List<Pair<IntRange, String>> =
    getLinkAnnotations(0, length)
        .mapNotNull {
            val item = it.item
            if (item is LinkAnnotation.Url) (it.start until it.end) to item.url else null
        }

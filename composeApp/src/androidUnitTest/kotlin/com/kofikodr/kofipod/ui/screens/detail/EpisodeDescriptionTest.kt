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

    // -------------------------------------------------------------------------
    // Scheme allowlist for anchor hrefs. Feed content is untrusted, so a
    // <a href="javascript:..."> must NOT become a tappable LinkAnnotation.Url.
    // Inner text is still appended (visible prose survives); only the link
    // annotation is dropped.
    // -------------------------------------------------------------------------

    @Test
    fun dropsJavascriptSchemeButKeepsInnerText() {
        val raw = """<a href="javascript:alert(1)">click here</a>"""
        val out = renderDescription(raw)
        assertEquals("click here", out.text, "Inner text must remain visible")
        assertTrue(out.urlLinks().isEmpty(), "javascript: must not produce a link annotation")
    }

    @Test
    fun dropsAndroidIntentScheme() {
        // intent:// URIs can launch arbitrary apps on Android; the only place
        // this surface is meant to come from is the app's own deep links,
        // never a podcast feed's <a>.
        val raw = """<a href="intent://launch#Intent;...end">tap</a>"""
        val out = renderDescription(raw)
        assertEquals("tap", out.text)
        assertTrue(out.urlLinks().isEmpty())
    }

    @Test
    fun dropsFileScheme() {
        val raw = """<a href="file:///etc/passwd">read</a>"""
        val out = renderDescription(raw)
        assertEquals("read", out.text)
        assertTrue(out.urlLinks().isEmpty())
    }

    @Test
    fun dropsDataScheme() {
        val raw = """<a href="data:text/html,<script>alert(1)</script>">payload</a>"""
        val out = renderDescription(raw)
        assertEquals("payload", out.text)
        assertTrue(out.urlLinks().isEmpty())
    }

    @Test
    fun dropsContentScheme() {
        // content:// is the Android ContentProvider scheme; another local app
        // could be hosting a malicious provider. Feed anchors must not be
        // able to deep-link into the ContentResolver.
        val raw = """<a href="content://com.evil/leak">link</a>"""
        val out = renderDescription(raw)
        assertEquals("link", out.text)
        assertTrue(out.urlLinks().isEmpty())
    }

    @Test
    fun dropsMailtoScheme_conservativelyForNow() {
        // mailto: IS arguably safe (and common in feeds), but the audit's
        // recommendation is strict http/https only.
        //
        // If mailto: is ever added to isSafeAnchorHref, this test must be
        // flipped to: assertEquals(1, out.urlLinks().size) AND a
        // `assertTrue(isSafeAnchorHref("mailto:..."))` row must be added to
        // isSafeAnchorHref_directHelperContract. Pinning the conservative
        // call so widening is an explicit policy change, not a silent drift.
        val raw = """<a href="mailto:hi@example.com">email us</a>"""
        val out = renderDescription(raw)
        assertEquals("email us", out.text)
        assertTrue(out.urlLinks().isEmpty())
    }

    @Test
    fun acceptsHttpAnchorAsLink() {
        // Pin the positive side so a too-tight scheme guard (e.g. https-only)
        // doesn't break legacy http podcast feeds. The platform's cleartext
        // policy will refuse to open http:// at the browser layer anyway,
        // but the link annotation should still exist.
        val raw = """<a href="http://oldschool.example/episode/1">old site</a>"""
        val out = renderDescription(raw)
        assertEquals(listOf("http://oldschool.example/episode/1"), out.urlLinks().map { it.second })
    }

    @Test
    fun acceptsUppercaseSchemeAsLink() {
        // RFC 3986: scheme is case-insensitive. Pin so a future tightening
        // doesn't accidentally reject HTTPS://… anchors that exist in real
        // feeds.
        val raw = """<a href="HTTPS://example.test">link</a>"""
        val out = renderDescription(raw)
        assertEquals("link", out.text, "Inner text must survive the scheme check")
        assertEquals(listOf("HTTPS://example.test"), out.urlLinks().map { it.second })
    }

    @Test
    fun acceptsHttpsAnchor_evenWhenQueryStringMentionsJavascript() {
        // Defensive: the guard checks the SCHEME, not the body. An https
        // URL with `javascript:` somewhere in the path/query is fine —
        // a future contributor who adds `contains("javascript:")` as
        // "extra safety" would break legitimate redirect-style URLs and
        // this test would catch the over-block.
        val raw = """<a href="https://safe.example/go?redirect=javascript:bad">link</a>"""
        val out = renderDescription(raw)
        assertEquals(
            listOf("https://safe.example/go?redirect=javascript:bad"),
            out.urlLinks().map { it.second },
        )
    }

    @Test
    fun trimWhitespacePrefixedHrefBeforeSchemeCheck() {
        // The renderer trims the href before passing to isSafeAnchorHref
        // (decodeEntities(...).trim()). Without that trim, "  https://..."
        // would fail the startsWith check and silently lose the link
        // annotation. This test pins that the trim is load-bearing so a
        // refactor that removes it from the renderer is caught.
        val raw = """<a href="  https://example.com">x</a>"""
        val out = renderDescription(raw)
        assertEquals(
            listOf("https://example.com"),
            out.urlLinks().map { it.second },
            "Whitespace-padded href must survive the trim and become a link",
        )
    }

    @Test
    fun dropsSchemelessRelativeHref() {
        // Relative URLs make no sense outside a feed page that has a base
        // URL we don't have. Drop them — the inner text remains visible.
        val raw = """<a href="/admin">admin panel</a>"""
        val out = renderDescription(raw)
        assertEquals("admin panel", out.text)
        assertTrue(out.urlLinks().isEmpty())
    }

    @Test
    fun isSafeAnchorHref_directHelperContract() {
        // Direct calls so a regression in the helper doesn't only show up
        // through the renderer's surface.
        assertTrue(isSafeAnchorHref("https://example.com"))
        assertTrue(isSafeAnchorHref("http://example.com"))
        assertTrue(isSafeAnchorHref("HTTPS://example.com"))
        assertTrue(isSafeAnchorHref("Http://example.com"))
        // Defensive positive: scheme is the contract, NOT the body. An https
        // URL whose path/query mentions "javascript:" must still pass.
        assertTrue(isSafeAnchorHref("https://x.com?redirect=javascript:alert(1)"))

        assertEquals(false, isSafeAnchorHref("javascript:alert(1)"))
        assertEquals(false, isSafeAnchorHref("intent://"))
        assertEquals(false, isSafeAnchorHref("file:///"))
        assertEquals(false, isSafeAnchorHref("data:text/plain,x"))
        assertEquals(false, isSafeAnchorHref("content://com.evil/"))
        assertEquals(false, isSafeAnchorHref(""))
        assertEquals(false, isSafeAnchorHref("mailto:x@example.com"))
        assertEquals(false, isSafeAnchorHref("/relative-path"))
        assertEquals(false, isSafeAnchorHref("//protocol-relative.example/"))
        // Real boundary cases for the startsWith check: "https" alone has no
        // "://" suffix, and "https:evil" is missing the slashes. Both must be
        // rejected; pin off-by-one edges.
        assertEquals(false, isSafeAnchorHref("https"))
        assertEquals(false, isSafeAnchorHref("https:evil"))
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

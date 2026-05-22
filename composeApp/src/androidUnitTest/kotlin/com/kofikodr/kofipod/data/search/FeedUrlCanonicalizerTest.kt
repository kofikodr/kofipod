// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.search

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [FeedUrlCanonicalizer]'s contract — the dedup key produced for each input.
 * The canonical form is **only** a dedup key, not a guaranteed-fetchable URL. We
 * still assert https-prefixed output to catch accidental scheme regressions.
 *
 * Each test asserts a specific transformation:
 *  - Same canonical output for two cosmetically-different inputs that describe the
 *    same feed.
 *  - Specific output for a single input where the rule's behavior is not obvious
 *    from the input alone (port stripping, fragment removal, tracking-param strip).
 */
class FeedUrlCanonicalizerTest {
    @Test
    fun blankInput_returnsBlank() {
        assertEquals("", FeedUrlCanonicalizer.canonicalize(""))
        assertEquals("", FeedUrlCanonicalizer.canonicalize("   "))
    }

    @Test
    fun hostlessInput_returnedAsIs() {
        // We do not invent a scheme for inputs that lack one.
        assertEquals("example.com/feed", FeedUrlCanonicalizer.canonicalize("example.com/feed"))
    }

    @Test
    fun httpForcedToHttps() {
        assertEquals(
            "https://example.com/feed",
            FeedUrlCanonicalizer.canonicalize("http://example.com/feed"),
        )
    }

    @Test
    fun upperCaseSchemeAndHostNormalised() {
        assertEquals(
            "https://example.com/Feed",
            FeedUrlCanonicalizer.canonicalize("HTTPS://Example.COM/Feed"),
        )
    }

    @Test
    fun pathCaseIsPreserved() {
        // Hosts often expose case-significant paths (e.g. show slugs); lowering them
        // would create false negatives in the dedup pass.
        val a = FeedUrlCanonicalizer.canonicalize("https://example.com/Show/Feed")
        val b = FeedUrlCanonicalizer.canonicalize("https://example.com/show/feed")
        assertEquals("https://example.com/Show/Feed", a)
        assertEquals("https://example.com/show/feed", b)
        // Explicitly verify they DON'T collapse to the same key.
        kotlin.test.assertNotEquals(a, b)
    }

    @Test
    fun defaultPortsStripped() {
        assertEquals(
            "https://example.com/feed",
            FeedUrlCanonicalizer.canonicalize("https://example.com:443/feed"),
        )
        assertEquals(
            "https://example.com/feed",
            FeedUrlCanonicalizer.canonicalize("http://example.com:80/feed"),
        )
    }

    @Test
    fun fragmentStripped() {
        assertEquals(
            "https://example.com/feed",
            FeedUrlCanonicalizer.canonicalize("https://example.com/feed#latest"),
        )
    }

    @Test
    fun trailingSlashCollapsed() {
        assertEquals(
            "https://example.com/feed",
            FeedUrlCanonicalizer.canonicalize("https://example.com/feed/"),
        )
    }

    @Test
    fun rootPathStaysEmpty() {
        // No path → no trailing slash to collapse; output must not invent a path.
        assertEquals("https://example.com", FeedUrlCanonicalizer.canonicalize("https://example.com"))
        assertEquals("https://example.com", FeedUrlCanonicalizer.canonicalize("https://example.com/"))
    }

    @Test
    fun trackingParamsStripped() {
        assertEquals(
            "https://example.com/feed",
            FeedUrlCanonicalizer.canonicalize(
                "https://example.com/feed?utm_source=itunes&utm_medium=app",
            ),
        )
        assertEquals(
            "https://example.com/feed",
            FeedUrlCanonicalizer.canonicalize("https://example.com/feed?fbclid=abc&gclid=def"),
        )
    }

    @Test
    fun functionalParamsPreserved() {
        // utm_* must go; non-tracking params must survive — many feeds use ?format=rss.
        assertEquals(
            "https://example.com/feed?format=rss",
            FeedUrlCanonicalizer.canonicalize(
                "https://example.com/feed?utm_source=x&format=rss&fbclid=y",
            ),
        )
    }

    @Test
    fun trackingParamMatchIsCaseInsensitive() {
        // utm_* match must not be defeated by camelCase querystrings.
        assertEquals(
            "https://example.com/feed",
            FeedUrlCanonicalizer.canonicalize("https://example.com/feed?UTM_Source=x"),
        )
    }

    @Test
    fun whitespaceTrimmed() {
        assertEquals(
            "https://example.com/feed",
            FeedUrlCanonicalizer.canonicalize("  https://example.com/feed  "),
        )
    }

    @Test
    fun nonHttpSchemeUntouched() {
        // We don't try to normalise file: / ftp: / itpc: even though some old podcatchers
        // ship them. Better to leave as-is than guess.
        assertEquals(
            "itpc://example.com/feed",
            FeedUrlCanonicalizer.canonicalize("itpc://example.com/feed"),
        )
    }

    @Test
    fun realWorldDuplicatesCollapseToSameKey() {
        val a =
            FeedUrlCanonicalizer.canonicalize(
                "http://feeds.megaphone.fm/SHOW123/?utm_source=itunes",
            )
        val b =
            FeedUrlCanonicalizer.canonicalize(
                "https://feeds.megaphone.fm/SHOW123",
            )
        assertEquals(a, b)
    }
}

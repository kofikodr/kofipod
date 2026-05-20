// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.ui.screens.detail.ai

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [googleSearchUrl]'s contract: tapping a People or Things row in the
 * Mentioned tab opens a Google search composed of `<name> <subtitle>`. Without
 * this:
 *  - blank subtitles would silently inject a trailing space into the query
 *    (Google tolerates it, but not all bookmarklet-aware browsers do);
 *  - non-ASCII names (Cyrillic, CJK, accented Latin) need percent-encoded
 *    bytes — a refactor that swaps the encoder must continue to spit out
 *    bytes the browser understands rather than literal UTF-16 code units.
 */
class MentionedTabUrlTest {
    @Test
    fun blankSubtitle_dropsTrailingSpace() {
        val url = googleSearchUrl(name = "Bret Victor", subtitle = "")
        assertEquals("https://www.google.com/search?q=Bret+Victor", url)
    }

    @Test
    fun subtitleFolded_intoQuery_forDisambiguation() {
        val url = googleSearchUrl(name = "Toby Lin", subtitle = "independent")
        assertEquals("https://www.google.com/search?q=Toby+Lin+independent", url)
    }

    @Test
    fun specialChars_arePercentEncoded() {
        // Apostrophes and slashes both routinely show up in podcast guest
        // names ("D'Angelo", "C/C++"). Without encoding, the literal chars
        // would either land as-is (browser tolerant, mostly) or break URI
        // parsers downstream — better to encode. Pin the full URL so a
        // partial regression (correct apostrophe but garbled remainder of
        // the same string) doesn't slip through a substring check.
        val url = googleSearchUrl(name = "Andre D'Angelo", subtitle = "")
        assertEquals("https://www.google.com/search?q=Andre+D%27Angelo", url)
    }

    @Test
    fun accentedLatin_encodesUtf8Bytes() {
        // 'é' is U+00E9 → UTF-8 0xC3 0xA9. A code-point-based encoder would have
        // produced "%E9" (or worse — wrong number of hex digits for code points
        // above 0xFF); the byte-based encoder must produce "%C3%A9".
        val url = googleSearchUrl(name = "café", subtitle = "")
        assertEquals("https://www.google.com/search?q=caf%C3%A9", url)
    }

    @Test
    fun cyrillic_encodesUtf8Bytes() {
        // Each Cyrillic code point is two UTF-8 bytes. Pin the full sequence so
        // a regression that emitted three-digit `%422` escapes (the original
        // bug) is loudly visible: the URL would simply not match.
        val url = googleSearchUrl(name = "Толстой", subtitle = "")
        assertEquals(
            "https://www.google.com/search?q=%D0%A2%D0%BE%D0%BB%D1%81%D1%82%D0%BE%D0%B9",
            url,
        )
    }

    @Test
    fun cjk_encodesUtf8Bytes() {
        // CJK characters are three UTF-8 bytes each. Soseki's name is a real
        // search a Japanese-literature podcast listener might tap.
        val url = googleSearchUrl(name = "夏目漱石", subtitle = "")
        assertEquals(
            "https://www.google.com/search?q=%E5%A4%8F%E7%9B%AE%E6%BC%B1%E7%9F%B3",
            url,
        )
    }

    @Test
    fun emojiAndSurrogatePair_encodesAllFourBytes() {
        // U+1F30D (🌍) is outside the BMP — Kotlin represents it as a UTF-16
        // surrogate pair, but UTF-8 encodes it as four bytes 0xF0 0x9F 0x8C 0x8D.
        // A char-iteration encoder would mishandle surrogates; the byte path
        // handles them correctly by going through encodeToByteArray() first.
        val url = googleSearchUrl(name = "🌍", subtitle = "")
        assertEquals("https://www.google.com/search?q=%F0%9F%8C%8D", url)
    }

    @Test
    fun mixedAsciiAndNonAscii_preservesAsciiAndEncodesRest() {
        // ASCII letters/digits/space must continue to round-trip cleanly even
        // when the string contains non-ASCII content. Pelé: P, e, l (ASCII)
        // then é (UTF-8 0xC3 0xA9).
        val url = googleSearchUrl(name = "Pelé", subtitle = "Brazil 1970")
        assertEquals("https://www.google.com/search?q=Pel%C3%A9+Brazil+1970", url)
    }
}

// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.ui.screens.detail.ai

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        // parsers downstream — better to encode.
        val url = googleSearchUrl(name = "Andre D'Angelo", subtitle = "")
        assertTrue(url.startsWith("https://www.google.com/search?q="))
        // %27 is the standard percent-encoding for apostrophe.
        assertTrue("%27" in url, "Apostrophe must percent-encode as %27 — saw $url")
    }
}

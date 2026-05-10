// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.pkm

import kotlin.test.Test
import kotlin.test.assertEquals

class SluggerTest {
    @Test fun lowercasesAndReplacesSpaces() = assertEquals("hello-world", slugify("Hello World"))

    @Test fun stripsPunctuationAndCollapsesRuns() = assertEquals("foo-bar-baz", slugify("Foo!! Bar  Baz"))

    @Test fun stripsAccents() = assertEquals("cafe-au-lait", slugify("Café Au Lait"))

    @Test fun stripsEmoji() = assertEquals("hello-world", slugify("Hello 🌎 World"))

    @Test fun truncatesToMaxLen() = assertEquals("abcdefghij", slugify("abcdefghijklmno", maxLen = 10))

    @Test fun blankFallsBackToUntitled() = assertEquals("untitled", slugify(""))

    @Test fun onlyPunctuationFallsBackToUntitled() = assertEquals("untitled", slugify("!!!---"))

    @Test fun trimmingHyphensAtEdges() = assertEquals("foo-bar", slugify("--foo-bar--"))

    @Test
    fun truncationLandingOnSeparatorReTrims() {
        // "foo! xyz" → pre-truncation slug is "foo-xyz". Length-4 cap drops the
        // 'xyz', leaving "foo-". The post-setLength trim must collapse the
        // dangling hyphen to "foo" — otherwise filenames produced near the
        // 32-char default cap could end with a stray '-' before ".md".
        assertEquals("foo", slugify("foo! xyz", maxLen = 4))
    }
}

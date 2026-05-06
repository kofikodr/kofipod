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
}

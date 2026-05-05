// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.search

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LibrarySearchQueryTest {
    @Test
    fun `blank input returns null`() {
        assertNull(LibrarySearchQuery.toFtsExpression(""))
        assertNull(LibrarySearchQuery.toFtsExpression("   "))
        assertNull(LibrarySearchQuery.toFtsExpression("\t\n "))
    }

    @Test
    fun `single word becomes prefix match`() {
        assertEquals("\"learning\"*", LibrarySearchQuery.toFtsExpression("learning"))
    }

    @Test
    fun `multiple words become AND of prefix matches`() {
        assertEquals(
            "\"continual\"* \"learning\"*",
            LibrarySearchQuery.toFtsExpression("continual learning"),
        )
    }

    @Test
    fun `embedded double quote is escaped by doubling`() {
        // FTS5 string literals double-quote-escape: "foo""bar"
        assertEquals(
            "\"it\"\"s\"*",
            LibrarySearchQuery.toFtsExpression("it\"s"),
        )
    }

    @Test
    fun `apostrophes and dashes survive without breaking the parser`() {
        // We don't strip — we let FTS5 tokenize them. Quoting is enough.
        assertEquals(
            "\"it's\"* \"co-pilot\"*",
            LibrarySearchQuery.toFtsExpression("it's co-pilot"),
        )
    }

    @Test
    fun `leading and trailing whitespace is trimmed before splitting`() {
        assertEquals("\"foo\"*", LibrarySearchQuery.toFtsExpression("   foo   "))
    }

    @Test
    fun `internal whitespace runs collapse to a single token boundary`() {
        assertEquals(
            "\"foo\"* \"bar\"*",
            LibrarySearchQuery.toFtsExpression("foo     bar"),
        )
    }
}

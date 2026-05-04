// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.opml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the parser against a realistic OPML fixture covering: a nested folder, a folder
 * whose name carries an XML entity (`&amp;`), a flat (un-foldered) feed, an entity in a
 * feed `xmlUrl`, and a non-rss `<outline>` (a bookmark-style link) that the parser
 * should drop. Drift here means the import path is about to mis-categorise feeds or
 * silently lose them.
 */
class OpmlParserTest {
    @Test
    fun parses_foldersAndFeeds_anddropsNonRssLinks() {
        val bytes = readFixtureBytes("opml/sample.opml")

        val doc = parseOpml(bytes)

        assertEquals("My subscriptions", doc.title)
        assertEquals(3, doc.outlines.size, "Expected 2 folders + 1 flat feed; bookmark link must be dropped")

        val tech = doc.outlines[0] as? OpmlOutline.Folder ?: fail("First outline should be a Folder")
        assertEquals("Tech", tech.name)
        assertEquals(2, tech.children.size)
        val atp = tech.children[0] as OpmlOutline.Feed
        // Per OPML 2.0, `text` is the required display field; `title` is informational and
        // only sometimes set. We prefer `text` so the surface matches what most readers show.
        assertEquals("ATP", atp.title, "Prefer text attr (the spec's display field) over title")
        assertEquals("https://atp.fm/rss", atp.xmlUrl)
        val soft = tech.children[1] as OpmlOutline.Feed
        assertEquals("Soft Skills Engineering", soft.title, "Use text when only it is present")

        val history = doc.outlines[1] as? OpmlOutline.Folder ?: fail("Second outline should be a Folder")
        assertEquals("History & Culture", history.name, "Folder name must round-trip the &amp; entity")
        val hh = history.children.single() as OpmlOutline.Feed
        assertEquals(
            "https://example.com/hh?id=1&fmt=rss",
            hh.xmlUrl,
            "xmlUrl must round-trip the &amp; entity",
        )

        val daily = doc.outlines[2] as? OpmlOutline.Feed ?: fail("Third outline should be a flat Feed")
        assertEquals("The Daily", daily.title)
        assertEquals("https://feeds.simplecast.com/54nAGcIl", daily.xmlUrl)
    }

    @Test
    fun missingOpmlRoot_throwsParseException() {
        val malformed = "<rss><channel><title>not opml</title></channel></rss>".toByteArray()
        try {
            parseOpml(malformed)
            fail("Expected OpmlParseException for missing <opml> root")
        } catch (e: OpmlParseException) {
            assertTrue(
                e.message!!.contains("opml", ignoreCase = true),
                "Error message should mention the missing root: was '${e.message}'",
            )
        }
    }

    @Test
    fun malformedXml_throwsParseException() {
        // Use unambiguously broken XML — an unterminated tag — rather than mismatched
        // nesting (which lenient pull parsers may accept). This guarantees the test
        // proves we surface parse errors rather than coincidentally passing on a recovered
        // doc.
        val malformed = "<opml version=\"2.0\"><body><outline".toByteArray()
        try {
            parseOpml(malformed)
            fail("Expected OpmlParseException on malformed XML")
        } catch (e: OpmlParseException) {
            assertTrue(
                e.message!!.contains("Malformed", ignoreCase = true) ||
                    e.message!!.contains("read", ignoreCase = true),
                "Error message should describe a parse/read failure: was '${e.message}'",
            )
        }
    }

    private fun readFixtureBytes(path: String): ByteArray {
        val resource = javaClass.classLoader!!.getResourceAsStream(path)
        assertNotNull(resource, "Fixture not found on classpath: $path")
        return resource.use { it.readBytes() }
    }
}

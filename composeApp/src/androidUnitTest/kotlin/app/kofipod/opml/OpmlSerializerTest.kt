// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.opml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Serializer is paired with the parser by [serializerOutput_roundTripsThroughParser]. The
 * other tests pin specific structural promises that downstream apps (Pocket Casts,
 * AntennaPod, Overcast) rely on: presence of `<opml version="2.0">`, an `xmlUrl`
 * attribute on every feed leaf, and proper escaping of XML-special characters.
 */
class OpmlSerializerTest {
    @Test
    fun emitsOpml2Header_andDocumentTitle() {
        val out =
            OpmlSerializer.serialize(
                title = "Test",
                folders = emptyList(),
                unfiled = emptyList(),
                generatedAtIso = "2026-05-04T00:00:00Z",
            )

        assertTrue(out.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(out.contains("<opml version=\"2.0\">"))
        assertTrue(out.contains("<title>Test</title>"))
        assertTrue(out.contains("<dateCreated>2026-05-04T00:00:00Z</dateCreated>"))
        assertTrue(out.trimEnd().endsWith("</opml>"))
    }

    @Test
    fun escapesXmlSpecials_inAllUserSuppliedFields() {
        val out =
            OpmlSerializer.serialize(
                title = "subs & more",
                folders =
                    listOf(
                        ExportFolder(
                            name = "A & B",
                            feeds =
                                listOf(
                                    ExportFeed(
                                        title = "<weird> \"quoted\"",
                                        xmlUrl = "https://example.com/?a=1&b=2",
                                    ),
                                ),
                        ),
                    ),
                unfiled = emptyList(),
                generatedAtIso = "now",
            )

        // The raw string `& B` (unescaped) MUST NOT appear — that would be invalid XML.
        assertTrue(out.contains("A &amp; B"))
        assertTrue(out.contains("&lt;weird&gt;"))
        assertTrue(out.contains("&quot;quoted&quot;"))
        assertTrue(out.contains("a=1&amp;b=2"))
    }

    @Test
    fun foldersNestFeeds_andUnfiledEmitAtRoot() {
        val out =
            OpmlSerializer.serialize(
                title = "Test",
                folders =
                    listOf(
                        ExportFolder("Tech", listOf(ExportFeed("ATP", "https://atp.fm/rss"))),
                    ),
                unfiled = listOf(ExportFeed("Daily", "https://example.com/daily.rss")),
                generatedAtIso = "now",
            )

        // Tech outline opens, contains feed, closes. Daily appears outside any folder.
        val techIdx = out.indexOf("<outline text=\"Tech\"")
        val techCloseIdx = out.indexOf("</outline>", techIdx)
        val atpIdx = out.indexOf("xmlUrl=\"https://atp.fm/rss\"")
        val dailyIdx = out.indexOf("xmlUrl=\"https://example.com/daily.rss\"")
        assertTrue(techIdx >= 0 && techCloseIdx > techIdx)
        assertTrue(atpIdx in techIdx..techCloseIdx, "ATP must be nested inside Tech")
        assertTrue(
            dailyIdx > techCloseIdx,
            "Daily must appear after </outline> closes Tech (i.e. at body root)",
        )
    }

    @Test
    fun serializerOutput_roundTripsThroughParser() {
        val folders =
            listOf(
                ExportFolder("Tech", listOf(ExportFeed("ATP", "https://atp.fm/rss"))),
                ExportFolder(
                    "History & Culture",
                    listOf(ExportFeed("Hardcore", "https://example.com/h?a=1&b=2")),
                ),
            )
        val unfiled = listOf(ExportFeed("Daily", "https://example.com/daily.rss"))
        val out =
            OpmlSerializer.serialize(
                title = "round-trip",
                folders = folders,
                unfiled = unfiled,
                generatedAtIso = "2026-05-04T00:00:00Z",
            )

        val parsed = parseOpml(out.toByteArray())

        assertEquals("round-trip", parsed.title)
        assertEquals(3, parsed.outlines.size)
        val tech = parsed.outlines[0] as OpmlOutline.Folder
        assertEquals("Tech", tech.name)
        assertEquals("https://atp.fm/rss", (tech.children.single() as OpmlOutline.Feed).xmlUrl)
        val history = parsed.outlines[1] as OpmlOutline.Folder
        assertEquals("History & Culture", history.name)
        assertEquals(
            "https://example.com/h?a=1&b=2",
            (history.children.single() as OpmlOutline.Feed).xmlUrl,
        )
        val daily = parsed.outlines[2] as OpmlOutline.Feed
        assertEquals("https://example.com/daily.rss", daily.xmlUrl)
    }
}

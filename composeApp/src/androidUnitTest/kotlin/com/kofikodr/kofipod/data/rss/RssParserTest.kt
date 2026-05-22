// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.rss

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [RssParser]'s contract against fixture feeds shaped like real publisher
 * output. The fixtures live under `androidUnitTest/resources/rss/`:
 *
 *  - `simplecast_well_formed.xml` — happy path. iTunes namespace + legacy `<image>`
 *    fallback + three episodes with three different `<itunes:duration>` shapes.
 *  - `edge_cases.xml` — missing GUID → enclosure-URL fallback; missing enclosure →
 *    drop the episode; malformed pubDate → keep episode with null pubDate; CDATA
 *    description with HTML entities; unknown namespace elements skipped without
 *    crashing.
 *  - `minimal_no_itunes.xml` — bare RSS 2.0, no iTunes namespace at all, must still
 *    parse with sensible defaults.
 *
 * Tests assert observable outcomes (returned data class fields), never parser
 * internals. The parser is pure so all assertions read the returned [RssChannel]
 * directly.
 */
class RssParserTest {
    @Test
    fun simplecast_parsesChannelFields() {
        val channel = RssParser.parse(readFixture("rss/simplecast_well_formed.xml"))
        assertEquals("The Sample Show", channel.title)
        assertEquals("https://example.com/sampleshow", channel.link)
        assertEquals("Sample Network", channel.author)
        // iTunes image wins over the legacy <image><url/></image> form when both present.
        assertEquals("https://art.example/sample/cover-1200.jpg", channel.imageUrl)
        // First <itunes:category>'s `text` attribute wins; nested sub-categories are skipped.
        assertEquals("News", channel.category)
    }

    @Test
    fun simplecast_parsesAllThreeEpisodes_inFeedOrder() {
        val channel = RssParser.parse(readFixture("rss/simplecast_well_formed.xml"))
        assertEquals(3, channel.episodes.size)
        assertEquals("sample-ep-0003", channel.episodes[0].guid)
        assertEquals("sample-ep-0002", channel.episodes[1].guid)
        assertEquals("sample-ep-0001", channel.episodes[2].guid)
    }

    @Test
    fun simplecast_parsesDurationInAllThreeShapes() {
        val channel = RssParser.parse(readFixture("rss/simplecast_well_formed.xml"))
        // Seconds-as-integer ("1820").
        assertEquals(1820L, channel.episodes[0].durationSeconds)
        // HH:MM:SS ("00:30:20").
        assertEquals(30 * 60 + 20L, channel.episodes[1].durationSeconds)
        // MM:SS ("28:45").
        assertEquals(28 * 60 + 45L, channel.episodes[2].durationSeconds)
    }

    @Test
    fun simplecast_pubDateParsesToCorrectInstant() {
        val channel = RssParser.parse(readFixture("rss/simplecast_well_formed.xml"))
        // "Fri, 22 May 2026 09:00:00 -0400" → 13:00:00 UTC.
        assertEquals(Instant.parse("2026-05-22T13:00:00Z"), channel.episodes[0].pubDate)
    }

    @Test
    fun simplecast_enclosureLengthParsed() {
        val channel = RssParser.parse(readFixture("rss/simplecast_well_formed.xml"))
        assertEquals(29384720L, channel.episodes[0].enclosure.lengthBytes)
        assertEquals("audio/mpeg", channel.episodes[0].enclosure.mimeType)
        assertEquals("https://audio.example/sample/3.mp3", channel.episodes[0].enclosure.url)
    }

    @Test
    fun simplecast_explicitParsedBothShapes() {
        val channel = RssParser.parse(readFixture("rss/simplecast_well_formed.xml"))
        // Episode 1 has <itunes:explicit>yes</itunes:explicit>.
        assertTrue(channel.episodes[2].explicit, "'yes' must read as true")
        // Episodes 2 and 3 both carry <itunes:explicit>false</itunes:explicit> literally.
        assertFalse(channel.episodes[0].explicit, "'false' string must read as false")
        assertFalse(channel.episodes[1].explicit, "'false' string must read as false")
    }

    @Test
    fun explicitElementAbsent_defaultsToFalse() {
        // The minimal fixture's item omits <itunes:explicit> entirely — the common
        // shape on pre-iTunes-namespace RSS. Default must be false (i.e. clean), not
        // tripped by a missing-element bug.
        val channel = RssParser.parse(readFixture("rss/minimal_no_itunes.xml"))
        assertFalse(channel.episodes.single().explicit)
    }

    @Test
    fun simplecast_episodeAndSeasonNumbersParsed() {
        val channel = RssParser.parse(readFixture("rss/simplecast_well_formed.xml"))
        assertEquals(3, channel.episodes[0].episodeNumber)
        assertEquals(1, channel.episodes[0].seasonNumber)
    }

    @Test
    fun simplecast_itunesEpisodeImageWinsOverChannelImage() {
        val channel = RssParser.parse(readFixture("rss/simplecast_well_formed.xml"))
        // Only the first episode has its own <itunes:image>.
        assertEquals("https://art.example/sample/ep3.jpg", channel.episodes[0].imageUrl)
        // The other two don't, and we don't currently inherit the channel image into
        // the episode (UI layer handles that fallback) — they should both be blank.
        // Asserting both so a regression that bleeds the channel image into episodes
        // without their own <itunes:image> can't slip past by only checking one.
        assertEquals("", channel.episodes[1].imageUrl)
        assertEquals("", channel.episodes[2].imageUrl)
    }

    @Test
    fun edgeCase_missingGuidFallsBackToEnclosureUrl() {
        val channel = RssParser.parse(readFixture("rss/edge_cases.xml"))
        val noGuidEpisode = channel.episodes.first { it.title == "No GUID episode" }
        // Reason: merge step (Slice B.3) needs SOME stable key. Enclosure URL is the
        // best fallback — it's stable across re-crawls until the publisher rotates it.
        assertEquals("https://audio.example/edges/no-guid.mp3", noGuidEpisode.guid)
    }

    @Test
    fun edgeCase_missingEnclosureDropsEpisode() {
        val channel = RssParser.parse(readFixture("rss/edge_cases.xml"))
        // Fixture has 5 items; item B has no <enclosure> and must be dropped.
        // The size assertion guards against a catastrophic regression where the parser
        // drops *every* episode — `none {}` alone would pass on an empty list.
        assertEquals(4, channel.episodes.size, "One no-enclosure item dropped, four others must survive")
        assertTrue(
            channel.episodes.none { it.title == "No enclosure episode (must be dropped)" },
            "Episode with no enclosure must be dropped, not surfaced with empty audio URL",
        )
    }

    @Test
    fun edgeCase_malformedPubDateKeepsEpisodeWithNullDate() {
        val channel = RssParser.parse(readFixture("rss/edge_cases.xml"))
        val badDateEpisode = channel.episodes.first { it.title == "Bad date episode" }
        // Reason: dropping an episode for an unparseable pubDate is too aggressive.
        // The audio is fine; the date just isn't displayable. Caller can render "—".
        assertNull(badDateEpisode.pubDate)
        assertEquals(
            "https://audio.example/edges/bad-date.mp3",
            badDateEpisode.enclosure.url,
            "Episode body must still be present even with null pubDate",
        )
        // The fixture's <enclosure> omits the `length` attribute. Null-when-absent
        // contract — not "default to 0" which would mislead callers reading bytes-size.
        assertNull(badDateEpisode.enclosure.lengthBytes)
    }

    @Test
    fun edgeCase_cdataDescriptionPreservesInnerText() {
        val channel = RssParser.parse(readFixture("rss/edge_cases.xml"))
        val cdataEpisode = channel.episodes.first { it.title.startsWith("CDATA description") }
        // CDATA content is delivered verbatim (the XML parser unwraps the wrapper).
        // We don't strip HTML at the parser layer — the UI layer renders it.
        assertTrue(
            cdataEpisode.description.contains("<em>emphasis</em>"),
            "CDATA HTML must be preserved verbatim for downstream rendering; got: ${cdataEpisode.description}",
        )
    }

    @Test
    fun edgeCase_unknownNamespaceElementsAreIgnored() {
        val channel = RssParser.parse(readFixture("rss/edge_cases.xml"))
        // The fixture's "Unknown namespace tolerance" item carries <podcast:transcript>
        // and <media:thumbnail>. They must be skipped without crashing or breaking the
        // surrounding fields.
        val episode = channel.episodes.first { it.title == "Unknown namespace tolerance" }
        assertEquals(42 * 60 + 15L, episode.durationSeconds)
    }

    @Test
    fun minimalRss_noItunesNamespace_stillParses() {
        val channel = RssParser.parse(readFixture("rss/minimal_no_itunes.xml"))
        assertEquals("Bare RSS", channel.title)
        // Defaults when iTunes namespace fields absent.
        assertEquals("", channel.author)
        assertEquals("", channel.imageUrl)
        assertEquals(1, channel.episodes.size)
        val ep = channel.episodes.single()
        assertEquals("bare-1", ep.guid)
        // "GMT" timezone parses as +0000.
        assertEquals(Instant.parse("2026-05-22T00:00:00Z"), ep.pubDate)
        assertNull(ep.durationSeconds, "Missing <itunes:duration> stays null")
    }

    @Test
    fun feedWithDoctype_returnsEmptyChannel_andDoesNotExpandEntities() {
        // XXE / billion-laughs defence. RSS 2.0 feeds don't legitimately use DOCTYPE,
        // so the parser short-circuits any feed shipping one. The parser must NOT
        // hand the bytes to xmlutil at all — different XML backends on different
        // platforms have different defaults around entity expansion and external
        // resolution. A feed with a recursive entity definition could blow up memory
        // or trigger a network fetch if forwarded.
        val hostile =
            """
            <?xml version="1.0"?>
            <!DOCTYPE rss [
              <!ENTITY a "AAAAA">
              <!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">
            ]>
            <rss version="2.0">
              <channel>
                <title>&b;</title>
                <item>
                  <title>Won't be parsed</title>
                  <guid>x</guid>
                  <enclosure url="https://x/a.mp3" type="audio/mpeg"/>
                </item>
              </channel>
            </rss>
            """.trimIndent()
        val result = RssParser.parse(hostile)
        // Empty channel — the contents are deliberately ignored rather than parsed.
        assertEquals("", result.title)
        assertEquals(emptyList(), result.episodes)
    }

    @Test
    fun containsDoctypeDeclaration_unit_caseAndWhitespaceTolerant() {
        assertTrue(RssParser.containsDoctypeDeclaration("<!DOCTYPE rss>"))
        assertTrue(RssParser.containsDoctypeDeclaration("<!doctype rss>"))
        assertTrue(RssParser.containsDoctypeDeclaration("<! DOCTYPE rss>"))
        assertTrue(
            RssParser.containsDoctypeDeclaration(
                "<?xml version=\"1.0\"?>\n  <!DOCTYPE rss>\n<rss/>",
            ),
        )
        // Must NOT trip on comments — `<!--`.
        assertFalse(RssParser.containsDoctypeDeclaration("<?xml version=\"1.0\"?><!-- a comment --><rss/>"))
        // Must NOT trip on plain text containing the word DOCTYPE outside the prolog.
        assertFalse(RssParser.containsDoctypeDeclaration("<rss><channel><title>doctype</title></channel></rss>"))
    }

    @Test
    fun parseDuration_unit_acceptsCommonShapes() {
        assertEquals(1820L, RssParser.parseDuration("1820"))
        assertEquals(1820L, RssParser.parseDuration("00:30:20"))
        assertEquals(1820L, RssParser.parseDuration("30:20"))
        assertNull(RssParser.parseDuration(""))
        assertNull(RssParser.parseDuration("not a duration"))
        // More-than-3 colon segments hit the else→null fallthrough — guards against a
        // future split() change that silently returns a wrong value for malformed input.
        assertNull(RssParser.parseDuration("1:2:3:4"))
    }

    @Test
    fun parseExplicit_unit_acceptsKnownTrueShapes() {
        assertTrue(RssParser.parseExplicit("yes"))
        assertTrue(RssParser.parseExplicit("true"))
        assertTrue(RssParser.parseExplicit("YES"))
        assertTrue(RssParser.parseExplicit("1"))
        // "explicit" is also in EXPLICIT_TRUE_VALUES — guards a refactor accidentally
        // dropping it from the set, which would silently flip explicit feeds to clean.
        assertTrue(RssParser.parseExplicit("explicit"))
        assertTrue(RssParser.parseExplicit("EXPLICIT"))
        assertFalse(RssParser.parseExplicit("no"))
        assertFalse(RssParser.parseExplicit("false"))
        assertFalse(RssParser.parseExplicit(""))
        assertFalse(RssParser.parseExplicit("clean"))
    }

    @Test
    fun parseRfc2822_unit_handlesNamedAndNumericZones() {
        // Named GMT.
        assertEquals(
            Instant.parse("2026-05-22T00:00:00Z"),
            RssParser.parseRfc2822("Wed, 22 May 2026 00:00:00 GMT"),
        )
        // Named EST (-5h) — note "EST" doesn't track DST so this is a deliberate
        // simplification we live with; the 99% of feeds use numeric offsets.
        assertEquals(
            Instant.parse("2026-05-22T05:00:00Z"),
            RssParser.parseRfc2822("22 May 2026 00:00:00 EST"),
        )
        // Numeric +0530.
        assertEquals(
            Instant.parse("2026-05-21T18:30:00Z"),
            RssParser.parseRfc2822("Fri, 22 May 2026 00:00:00 +0530"),
        )
        // Day-of-week prefix optional.
        assertEquals(
            Instant.parse("2026-05-22T00:00:00Z"),
            RssParser.parseRfc2822("22 May 2026 00:00:00 +0000"),
        )
        // Garbage → null.
        assertNull(RssParser.parseRfc2822("yesterday"))
        assertNull(RssParser.parseRfc2822(""))
        // Seconds are optional in RFC 2822; default to :00 when absent.
        assertEquals(
            Instant.parse("2026-05-22T09:00:00Z"),
            RssParser.parseRfc2822("22 May 2026 09:00 +0000"),
        )
    }

    private fun readFixture(path: String): String {
        val resource = javaClass.classLoader!!.getResourceAsStream(path)
        assertNotNull(resource, "Fixture not found on classpath: $path")
        return resource.bufferedReader().use { it.readText() }
    }
}

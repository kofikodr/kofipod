// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.opml

import app.kofipod.data.repo.LibraryRepository
import app.kofipod.domain.PodcastSummary
import app.kofipod.testing.inMemoryDatabase
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exercises the merge rules in [OpmlRepository.import] against a real (in-memory) DB.
 * What we're verifying is the *contract* between OPML structure and library state: the
 * dedup-by-feedUrl rule, folder-reuse-by-name rule, and "failed lookups don't poison
 * the run" rule. The XML side is pinned in OpmlParserTest; this test bypasses the parse
 * by feeding bytes through [parseOpml] like production does.
 */
class OpmlRepositoryTest {
    private val fixedClock =
        object : Clock {
            override fun now(): Instant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        }

    @Test
    fun import_resolvesFeeds_createsFolders_andPlacesPodcasts() =
        runTest {
            val db = inMemoryDatabase()
            val library = LibraryRepository(db)
            val lookup =
                FakeLookup(
                    "https://atp.fm/rss" to summary("123", "ATP", "https://atp.fm/rss"),
                    "https://feeds.simplecast.com/54nAGcIl" to
                        summary("999", "The Daily", "https://feeds.simplecast.com/54nAGcIl"),
                )
            val repo = OpmlRepository(library, lookup, fixedClock)

            val result = repo.import(SAMPLE_OPML.toByteArray())

            assertEquals(2, result.imported)
            assertEquals(0, result.skipped)
            assertEquals(0, result.failed)

            val lists = library.listsNow()
            assertEquals(1, lists.size, "Single folder in OPML → single PodcastList row")
            assertEquals("Tech", lists.single().name)

            val podcasts = library.podcastsNow().sortedBy { it.title }
            assertEquals(2, podcasts.size)
            val atp = podcasts.first { it.title == "ATP" }
            assertEquals(lists.single().id, atp.listId, "ATP must land in the Tech folder")
            val daily = podcasts.first { it.title == "The Daily" }
            assertEquals(null, daily.listId, "The Daily was at body root → unfiled")
        }

    @Test
    fun import_skipsFeedsAlreadyInLibrary_preservingExistingFolderMembership() =
        runTest {
            val db = inMemoryDatabase()
            val library = LibraryRepository(db)
            // Pre-seed: ATP already exists in a "Favourites" folder.
            library.createList(id = "favourites", name = "Favourites", position = 0, now = 0L)
            library.savePodcast(
                summary = summary("123", "ATP", "https://atp.fm/rss"),
                listId = "favourites",
                now = 0L,
            )
            val lookup =
                FakeLookup(
                    "https://feeds.simplecast.com/54nAGcIl" to
                        summary("999", "The Daily", "https://feeds.simplecast.com/54nAGcIl"),
                    // ATP intentionally not bound — a re-import must not re-resolve it.
                )
            val repo = OpmlRepository(library, lookup, fixedClock)

            val result = repo.import(SAMPLE_OPML.toByteArray())

            assertEquals(1, result.imported, "Only The Daily should be newly imported")
            assertEquals(1, result.skipped, "ATP must be counted as skipped (already in library)")
            assertEquals(0, result.failed)

            val atp = library.podcastsNow().first { it.title == "ATP" }
            assertEquals("favourites", atp.listId, "Existing folder membership must be preserved")
        }

    @Test
    fun import_reusesExistingFolderByName_caseInsensitive() =
        runTest {
            val db = inMemoryDatabase()
            val library = LibraryRepository(db)
            library.createList(id = "tech", name = "tech", position = 0, now = 0L)
            val lookup =
                FakeLookup(
                    "https://atp.fm/rss" to summary("123", "ATP", "https://atp.fm/rss"),
                    "https://feeds.simplecast.com/54nAGcIl" to
                        summary("999", "The Daily", "https://feeds.simplecast.com/54nAGcIl"),
                )
            val repo = OpmlRepository(library, lookup, fixedClock)

            repo.import(SAMPLE_OPML.toByteArray())

            assertEquals(1, library.listsNow().size, "OPML 'Tech' must reuse the existing 'tech' list")
        }

    @Test
    fun import_failedLookupsAreCounted_butDontStopOtherFeeds() =
        runTest {
            val db = inMemoryDatabase()
            val library = LibraryRepository(db)
            // Only bind the second URL → the first will fail to resolve.
            val lookup =
                FakeLookup(
                    "https://feeds.simplecast.com/54nAGcIl" to
                        summary("999", "The Daily", "https://feeds.simplecast.com/54nAGcIl"),
                )
            val repo = OpmlRepository(library, lookup, fixedClock)

            val result = repo.import(SAMPLE_OPML.toByteArray())

            assertEquals(1, result.imported)
            assertEquals(1, result.failed)
            assertTrue("ATP" in result.failedTitles)
            // The successful import must still have happened.
            assertNotNull(library.podcastsNow().firstOrNull { it.title == "The Daily" })
        }

    @Test
    fun import_nestedFolders_useInnermostName_andDoNotInherit() =
        runTest {
            val db = inMemoryDatabase()
            val library = LibraryRepository(db)
            val nested =
                """<?xml version="1.0" encoding="UTF-8"?>
                <opml version="2.0">
                  <head><title>Nested</title></head>
                  <body>
                    <outline text="Tech" title="Tech">
                      <outline text="Swift" title="Swift">
                        <outline type="rss" text="Swift by Sundell" xmlUrl="https://swiftbysundell.com/feed.rss"/>
                      </outline>
                    </outline>
                  </body>
                </opml>"""
            val lookup =
                FakeLookup(
                    "https://swiftbysundell.com/feed.rss" to
                        summary("42", "Swift by Sundell", "https://swiftbysundell.com/feed.rss"),
                )
            val repo = OpmlRepository(library, lookup, fixedClock)

            repo.import(nested.toByteArray())

            val lists = library.listsNow()
            // Both folders create lists; the podcast lands in the innermost ("Swift") so
            // the most specific label is preserved rather than silently flattening to "Tech".
            assertTrue(lists.any { it.name == "Tech" })
            val swift = lists.firstOrNull { it.name == "Swift" }
            assertNotNull(swift, "Inner folder name must produce a 'Swift' list")
            val podcast = library.podcastsNow().single()
            assertEquals(swift.id, podcast.listId, "Podcast must land in the innermost folder")
        }

    @Test
    fun import_dedup_preservesPathCase() =
        runTest {
            val db = inMemoryDatabase()
            val library = LibraryRepository(db)
            // Pre-seed a podcast with a lowercase path. The OPML uses uppercase `/Feed.rss`,
            // which is a distinct resource on case-sensitive servers — must NOT be skipped.
            library.savePodcast(
                summary = summary("1", "Lower", "https://example.com/feed.rss"),
                listId = null,
                now = 0L,
            )
            val opml =
                """<?xml version="1.0" encoding="UTF-8"?>
                <opml version="2.0">
                  <head/>
                  <body>
                    <outline type="rss" text="Upper" xmlUrl="https://example.com/Feed.rss"/>
                  </body>
                </opml>"""
            val lookup =
                FakeLookup(
                    "https://example.com/Feed.rss" to
                        summary("2", "Upper", "https://example.com/Feed.rss"),
                )
            val repo = OpmlRepository(library, lookup, fixedClock)

            val result = repo.import(opml.toByteArray())

            assertEquals(1, result.imported, "Path-case difference must be treated as distinct URL")
            assertEquals(0, result.skipped)
            assertEquals(2, library.podcastsNow().size)
        }

    @Test
    fun export_roundTripsThroughParser() =
        runTest {
            val db = inMemoryDatabase()
            val library = LibraryRepository(db)
            library.createList(id = "tech", name = "Tech", position = 0, now = 0L)
            library.savePodcast(summary("1", "ATP", "https://atp.fm/rss"), listId = "tech", now = 0L)
            library.savePodcast(
                summary("2", "Daily", "https://example.com/daily.rss"),
                listId = null,
                now = 0L,
            )
            val repo = OpmlRepository(library, FakeLookup(), fixedClock)

            val xml = repo.export()
            val parsed = parseOpml(xml.toByteArray())

            assertEquals(2, parsed.outlines.size, "One folder + one unfiled feed")
            val tech = parsed.outlines.filterIsInstance<OpmlOutline.Folder>().single()
            assertEquals("Tech", tech.name)
            assertEquals("https://atp.fm/rss", (tech.children.single() as OpmlOutline.Feed).xmlUrl)
            val daily = parsed.outlines.filterIsInstance<OpmlOutline.Feed>().single()
            assertEquals("https://example.com/daily.rss", daily.xmlUrl)
        }

    private fun summary(
        id: String,
        title: String,
        feedUrl: String,
    ): PodcastSummary =
        PodcastSummary(
            id = id,
            feedId = id.toLongOrNull() ?: 0L,
            title = title,
            author = "",
            description = "",
            artworkUrl = "",
            feedUrl = feedUrl,
        )

    private class FakeLookup(vararg pairs: Pair<String, PodcastSummary>) : PodcastFeedLookup {
        private val map = pairs.toMap()

        override suspend fun resolve(feedUrl: String): PodcastSummary = map[feedUrl] ?: error("Not in fake lookup: $feedUrl")
    }

    private companion object {
        const val SAMPLE_OPML = """<?xml version="1.0" encoding="UTF-8"?>
<opml version="2.0">
  <head><title>Sample</title></head>
  <body>
    <outline text="Tech" title="Tech">
      <outline type="rss" text="ATP" xmlUrl="https://atp.fm/rss"/>
    </outline>
    <outline type="rss" text="The Daily" xmlUrl="https://feeds.simplecast.com/54nAGcIl"/>
  </body>
</opml>"""
    }
}

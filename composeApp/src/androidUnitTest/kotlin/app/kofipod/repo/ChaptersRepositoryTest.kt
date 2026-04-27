// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.repo

import app.kofipod.data.repo.ChaptersRepository
import app.kofipod.data.repo.parseChapters
import app.kofipod.testing.inMemoryDatabase
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChaptersRepositoryTest {
    // -----------------------------------------------------------------------------
    // parseChapters — pure conversion tests. These lock down the float-seconds → ms
    // rounding contract that is otherwise easy to silently regress.
    // -----------------------------------------------------------------------------

    @Test
    fun parseChapters_convertsFractionalStartTimeToMillis() {
        val json =
            """
            {
              "version": "1.2.0",
              "chapters": [
                { "startTime": 1.5, "title": "Half-second start" }
              ]
            }
            """.trimIndent()
        val rows = parseChapters("ep-1", json)
        assertEquals(1, rows.size)
        assertEquals(1500L, rows[0].startMs, "1.5 s should round to 1500 ms exactly")
    }

    @Test
    fun parseChapters_roundsStartTimeAtMillisecondBoundary() {
        // 0.9995 s * 1000 = 999.5 → roundToLong half-even rounds to nearest *even* —
        // for 999.5 that's 1000. This pins the rounding contract; if Kotlin/Native or
        // the JVM ever change rounding mode, this catches it.
        val json =
            """
            { "chapters": [ { "startTime": 0.9995, "title": "Boundary" } ] }
            """.trimIndent()
        val rows = parseChapters("ep-1", json)
        assertEquals(1000L, rows[0].startMs, "0.9995 s should round to 1000 ms (half-to-even)")
    }

    @Test
    fun parseChapters_treatsNullStartTimeAsZero() {
        val json =
            """
            { "chapters": [ { "title": "No start time" } ] }
            """.trimIndent()
        val rows = parseChapters("ep-1", json)
        assertEquals(0L, rows[0].startMs)
    }

    @Test
    fun parseChapters_clampsNegativeStartTimeToZero() {
        // Defensive: spec is non-negative, but a malformed feed shouldn't write
        // negative offsets that downstream seek-to-time math then has to handle.
        val json =
            """
            { "chapters": [ { "startTime": -1.0, "title": "Bad data" } ] }
            """.trimIndent()
        val rows = parseChapters("ep-1", json)
        assertEquals(0L, rows[0].startMs)
    }

    @Test
    fun parseChapters_dropsChaptersWithBlankOrMissingTitle() {
        val json =
            """
            {
              "chapters": [
                { "startTime": 0.0, "title": "Cold open" },
                { "startTime": 60.0, "title": "" },
                { "startTime": 120.0 },
                { "startTime": 180.0, "title": "   " },
                { "startTime": 240.0, "title": "Outro" }
              ]
            }
            """.trimIndent()
        val rows = parseChapters("ep-1", json)
        assertEquals(2, rows.size, "Empty / missing / whitespace titles should be dropped")
        assertEquals(listOf("Cold open", "Outro"), rows.map { it.title })
        // `seq` reflects source-array index, not post-filter — the gap is intentional
        // so a future "show me chapter N from the source" query maps cleanly.
        assertEquals(listOf(0L, 4L), rows.map { it.seq })
    }

    @Test
    fun parseChapters_preservesOptionalImageAndLinkAndDefaultsToEmpty() {
        val json =
            """
            {
              "chapters": [
                { "startTime": 0.0, "title": "With assets", "img": "https://e.example/i.png", "url": "https://e.example/notes" },
                { "startTime": 30.0, "title": "Without assets" }
              ]
            }
            """.trimIndent()
        val rows = parseChapters("ep-1", json)
        assertEquals("https://e.example/i.png", rows[0].imageUrl)
        assertEquals("https://e.example/notes", rows[0].linkUrl)
        assertEquals("", rows[1].imageUrl, "Missing img should default to empty, not null")
        assertEquals("", rows[1].linkUrl)
    }

    @Test
    fun parseChapters_handlesEmptyChaptersArray() {
        val json = """{ "chapters": [] }"""
        assertEquals(emptyList(), parseChapters("ep-1", json))
    }

    @Test
    fun parseChapters_handlesMissingChaptersField() {
        val json = """{ "version": "1.2.0" }"""
        assertEquals(emptyList(), parseChapters("ep-1", json))
    }

    // -----------------------------------------------------------------------------
    // ensureCached — integration tests. Verify cache-hit short-circuit and the
    // mutex guard that prevents concurrent fetches for the same episode.
    // -----------------------------------------------------------------------------

    @Test
    fun ensureCached_returnsImmediatelyWhenCacheAlreadyPopulated() =
        runTest {
            val db = inMemoryDatabase()
            // Pre-seed one chapter row.
            db.episodeChapterQueries.insert(
                episodeId = "ep-1",
                seq = 0,
                startMs = 0L,
                title = "Already cached",
                imageUrl = "",
                linkUrl = "",
            )
            val callCount = AtomicInteger(0)
            val client =
                HttpClient(
                    MockEngine { _ ->
                        callCount.incrementAndGet()
                        respond("{ \"chapters\": [] }", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                    },
                )
            val repo = ChaptersRepository(db, client)

            val result = repo.ensureCached("ep-1", "https://example.com/chapters.json")

            assertTrue(result.isSuccess)
            assertEquals(0, callCount.get(), "ensureCached must not hit the network when rows are already cached")
        }

    @Test
    fun ensureCached_underConcurrentCalls_fetchesExactlyOnce() =
        runTest {
            val db = inMemoryDatabase()
            val callCount = AtomicInteger(0)
            val payload =
                """
                { "chapters": [ { "startTime": 0.0, "title": "Intro" }, { "startTime": 60.0, "title": "Body" } ] }
                """.trimIndent()
            val client =
                HttpClient(
                    MockEngine { _ ->
                        callCount.incrementAndGet()
                        respond(
                            ByteReadChannel(payload),
                            HttpStatusCode.OK,
                            headersOf("Content-Type", "application/json"),
                        )
                    },
                )
            val repo = ChaptersRepository(db, client)

            // Two concurrent callers for the same episode. The mutex inside ensureCached
            // serialises them so the second sees a populated cache and bails.
            val a = async { repo.ensureCached("ep-1", "https://example.com/chapters.json") }
            val b = async { repo.ensureCached("ep-1", "https://example.com/chapters.json") }
            a.await()
            b.await()

            assertEquals(1, callCount.get(), "Mutex must collapse concurrent ensureCached calls into one network round-trip")
            val rows = db.episodeChapterQueries.selectByEpisode("ep-1").executeAsList()
            assertEquals(2, rows.size, "Cache should be populated with the single fetched payload")
        }

    @Test
    fun refresh_returnsFailureWhenServerReturnsNon2xx() =
        runTest {
            val db = inMemoryDatabase()
            val client = HttpClient(MockEngine { _ -> respondError(HttpStatusCode.NotFound) })
            val repo = ChaptersRepository(db, client)

            val result = repo.refresh("ep-1", "https://example.com/missing.json")

            assertTrue(result.isFailure, "404 should surface as Result.failure (callers observe the persisted Flow)")
            // No partial writes from a failed fetch.
            assertEquals(0L, db.episodeChapterQueries.countByEpisode("ep-1").executeAsOne())
        }

    @Test
    fun refresh_replacesExistingChaptersAtomically() =
        runTest {
            val db = inMemoryDatabase()
            // Pre-seed three stale rows.
            (0..2).forEach { idx ->
                db.episodeChapterQueries.insert(
                    episodeId = "ep-1",
                    seq = idx.toLong(),
                    startMs = (idx * 1000).toLong(),
                    title = "Stale $idx",
                    imageUrl = "",
                    linkUrl = "",
                )
            }
            val payload = """{ "chapters": [ { "startTime": 0.0, "title": "Fresh single" } ] }"""
            val client =
                HttpClient(
                    MockEngine { _ ->
                        respond(payload, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                    },
                )
            val repo = ChaptersRepository(db, client)

            repo.refresh("ep-1", "https://example.com/chapters.json")

            val rows = db.episodeChapterQueries.selectByEpisode("ep-1").executeAsList()
            assertEquals(1, rows.size, "Refresh must replace, not append (the deleteByEpisode + insert transaction)")
            assertEquals("Fresh single", rows[0].title)
        }
}

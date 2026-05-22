// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import com.kofikodr.kofipod.data.net.kofipodJson
import com.kofikodr.kofipod.data.search.ItunesStorefront
import com.kofikodr.kofipod.domain.SourceId
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpRequestData
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract for [ItunesSearchApi]. The wrapper is a thin Ktor adapter, so the tests
 * focus on:
 *  - The HTTP request shape we send (media/entity/country/limit/attribute params).
 *  - The response → [com.kofikodr.kofipod.domain.PodcastSummary] mapping (id
 *    prefix, sentinel feedId, artwork upscale, missing-feedUrl filter).
 *
 * `MockEngine` lets us pin both — it captures every request and returns a canned
 * body, so the assertions read the request URL directly rather than via implementation
 * internals.
 */
class ItunesSearchApiTest {
    @Test
    fun search_setsMediaAndEntityParams() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api = apiReturning(SAMPLE_TWO_RESULTS, captured)
            api.search(term = "test", storefront = ItunesStorefront.UnitedStates)

            val req = captured.single()
            val params = req.url.parameters
            assertEquals("podcast", params["media"], "media param must be 'podcast' to scope away from music")
            assertEquals("podcast", params["entity"], "entity param must be 'podcast'")
            assertEquals("test", params["term"])
            assertEquals("US", params["country"])
        }

    @Test
    fun search_passesStorefrontCode() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api = apiReturning("""{"resultCount":0,"results":[]}""", captured)
            api.search(term = "x", storefront = ItunesStorefront.Germany)
            assertEquals("DE", captured.single().url.parameters["country"])
        }

    @Test
    fun search_titleTermAttribute_isSent() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api = apiReturning("""{"resultCount":0,"results":[]}""", captured)
            api.search(
                term = "x",
                storefront = ItunesStorefront.UnitedStates,
                attribute = ItunesSearchApi.SearchAttribute.TitleTerm,
            )
            assertEquals("titleTerm", captured.single().url.parameters["attribute"])
        }

    @Test
    fun search_anyAttribute_omitsAttributeParam() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api = apiReturning("""{"resultCount":0,"results":[]}""", captured)
            api.search(
                term = "x",
                storefront = ItunesStorefront.UnitedStates,
                attribute = ItunesSearchApi.SearchAttribute.Any,
            )
            assertEquals(null, captured.single().url.parameters["attribute"])
        }

    @Test
    fun search_limitClampedToMax() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api = apiReturning("""{"resultCount":0,"results":[]}""", captured)
            api.search(term = "x", storefront = ItunesStorefront.UnitedStates, limit = 9_999)
            assertEquals(ItunesSearchApi.MAX_LIMIT.toString(), captured.single().url.parameters["limit"])
        }

    @Test
    fun blankTerm_skipsHttpEntirely() =
        runTest {
            val captured = mutableListOf<HttpRequestData>()
            val api = apiReturning(SAMPLE_TWO_RESULTS, captured)
            val results = api.search(term = "   ", storefront = ItunesStorefront.UnitedStates)
            assertEquals(emptyList(), results)
            assertEquals(0, captured.size, "Blank term must not hit the network at all")
        }

    @Test
    fun parsesResults_intoSummariesWithItunesPrefix() =
        runTest {
            val api = apiReturning(SAMPLE_TWO_RESULTS, mutableListOf())
            val results = api.search(term = "x", storefront = ItunesStorefront.UnitedStates)
            assertEquals(2, results.size)
            assertTrue(
                results.all { it.id.startsWith(ITUNES_ID_PREFIX) },
                "Every iTunes result must use the itunes: id prefix so SearchViewModel knows to hydrate before navigation",
            )
            assertTrue(
                results.all { it.feedId == 0L },
                "iTunes results must use the 0L feedId sentinel until PI hydration sets a real one",
            )
            assertTrue(
                results.all { it.sources == setOf(SourceId.ITunes) },
                "Every iTunes result must carry exactly the ITunes source tag",
            )
        }

    @Test
    fun resultMissingFeedUrl_isFilteredOut() =
        runTest {
            val api =
                apiReturning(
                    """{"resultCount":2,"results":[
                        {"collectionId":1,"collectionName":"WithFeed","artistName":"A","feedUrl":"https://x.com/feed"},
                        {"collectionId":2,"collectionName":"NoFeed","artistName":"B"}
                    ]}""",
                    mutableListOf(),
                )
            val results = api.search(term = "x", storefront = ItunesStorefront.UnitedStates)
            // Subscribing to a feed-less show is meaningless; the wrapper must drop it.
            assertEquals(1, results.size)
            assertEquals("WithFeed", results.single().title)
        }

    @Test
    fun artwork600_isUpscaledToHigherDensity() =
        runTest {
            val api =
                apiReturning(
                    """{"resultCount":1,"results":[
                        {"collectionId":7,"collectionName":"X","artistName":"A","feedUrl":"https://x.com/f","artworkUrl600":"https://art.com/abc.600x600bb.jpg"}
                    ]}""",
                    mutableListOf(),
                )
            val results = api.search(term = "x", storefront = ItunesStorefront.UnitedStates)
            assertEquals("https://art.com/abc.1200x1200bb.jpg", results.single().artworkUrl)
        }

    @Test
    fun trackNameFallsBackForCollectionName() =
        runTest {
            // collectionName is occasionally blank on episodes / older shows; trackName
            // must be used so the result is still display-ready.
            val api =
                apiReturning(
                    """{"resultCount":1,"results":[
                        {"collectionId":7,"trackName":"From Track","artistName":"A","feedUrl":"https://x.com/f"}
                    ]}""",
                    mutableListOf(),
                )
            val results = api.search(term = "x", storefront = ItunesStorefront.UnitedStates)
            assertEquals("From Track", results.single().title)
        }

    @Test
    fun unknownJsonFields_doNotBreakDecode() =
        runTest {
            // Apple regularly adds new fields. The shared Json config is lenient, so a
            // future kind/releaseDate/genreIds addition must not crash the decode.
            val api =
                apiReturning(
                    """{"resultCount":1,"unexpectedTopLevel":"???","results":[
                        {"collectionId":7,"collectionName":"X","artistName":"A","feedUrl":"https://x.com/f","futureField":42}
                    ]}""",
                    mutableListOf(),
                )
            val results = api.search(term = "x", storefront = ItunesStorefront.UnitedStates)
            assertEquals(1, results.size)
        }

    @Test
    fun upscaleArtwork_handlesNullAndBlank() {
        assertEquals("", ItunesSearchApi.upscaleArtwork(null))
        assertEquals("", ItunesSearchApi.upscaleArtwork(""))
        assertEquals("", ItunesSearchApi.upscaleArtwork("   "))
    }

    @Test
    fun upscaleArtwork_handles100xVariant() {
        // Smaller artworkUrl100 falls through the same upscale rule.
        assertEquals(
            "https://art.com/abc.1200x1200bb.jpg",
            ItunesSearchApi.upscaleArtwork("https://art.com/abc.100x100bb.jpg"),
        )
    }

    @Test
    fun isItunesOnlyId_matchesOnlyItunesIds() {
        assertTrue("itunes:12345".isItunesOnlyId())
        assertTrue(!("12345".isItunesOnlyId()))
        assertTrue(!("".isItunesOnlyId()))
    }

    private fun apiReturning(
        body: String,
        captured: MutableList<HttpRequestData>,
    ): ItunesSearchApi {
        val engine =
            MockEngine { request ->
                captured.add(request)
                respond(
                    content = body,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        val client =
            HttpClient(engine) {
                install(ContentNegotiation) { json(kofipodJson) }
            }
        return ItunesSearchApi(client)
    }

    companion object {
        /**
         * Minimal but realistic iTunes Search API response — two podcast entries with
         * the fields we map (collectionId, collectionName, artistName, feedUrl,
         * artworkUrl600, primaryGenreName, trackCount). Fields we don't map (kind,
         * releaseDate, genreIds, etc.) are absent — the [kofipodJson] config tolerates
         * both presence and absence.
         */
        private val SAMPLE_TWO_RESULTS: String =
            """
            {
              "resultCount": 2,
              "results": [
                {
                  "collectionId": 1000000001,
                  "trackId": 1000000001,
                  "collectionName": "The Daily",
                  "trackName": "The Daily",
                  "artistName": "The New York Times",
                  "feedUrl": "https://feeds.simplecast.com/54nAGcIl",
                  "artworkUrl600": "https://art.com/daily.600x600bb.jpg",
                  "primaryGenreName": "News",
                  "trackCount": 1234
                },
                {
                  "collectionId": 1000000002,
                  "trackId": 1000000002,
                  "collectionName": "Conan O'Brien Needs A Friend",
                  "trackName": "Conan O'Brien Needs A Friend",
                  "artistName": "Team Coco",
                  "feedUrl": "https://feeds.simplecast.com/abc",
                  "artworkUrl600": "https://art.com/conan.600x600bb.jpg",
                  "primaryGenreName": "Comedy",
                  "trackCount": 250
                }
              ]
            }
            """.trimIndent()
    }
}

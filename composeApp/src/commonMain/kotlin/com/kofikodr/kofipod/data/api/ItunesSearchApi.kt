// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import com.kofikodr.kofipod.data.net.kofipodJson
import com.kofikodr.kofipod.data.search.ItunesStorefront
import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.domain.SourceId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText

/**
 * Apple iTunes Search API wrapper. No auth required, no SDK — a thin Ktor client over
 * `https://itunes.apple.com/search`. Used as the second [com.kofikodr.kofipod.data.repo.SearchSource]
 * alongside Podcast Index to catch podcasts the primary index doesn't have or hasn't
 * indexed yet.
 *
 * Endpoint: `GET /search?media=podcast&entity=podcast&term={q}&country={iso2}&limit={n}`
 *
 *  - `media=podcast&entity=podcast` constrains results to podcast shows (not episodes,
 *    not music). This is non-optional — without it, queries match across iTunes Music
 *    and the App Store too.
 *  - `attribute=titleTerm` is supported for title-narrow search; absence searches all
 *    indexed fields.
 *  - `country` is the user-picked storefront (see [ItunesStorefront]).
 *  - There is no `attribute=person` equivalent that returns podcasts, so
 *    [com.kofikodr.kofipod.data.repo.ItunesSearchRepository.searchByPerson] returns an
 *    empty list and the aggregator passes Podcast Index's person search through unchanged.
 *
 * Rate limits: Apple does not publish a hard cap, but in practice ~20 req/sec from
 * one IP is comfortable. The shared 600ms debounce on the SearchViewModel + per-source
 * 1.2s timeout in [com.kofikodr.kofipod.data.repo.AggregateSearchSource] keeps us well
 * under that on realistic typing.
 *
 * **The shared HttpClient must not have the Ktor `Logging` plugin installed** — iTunes
 * URLs don't carry secrets, but the Podcast Index calls share the same client and
 * could log their auth headers if logging were added. The factory comment in
 * `HttpClientFactory.android.kt` is authoritative.
 */
class ItunesSearchApi(private val client: HttpClient) {
    suspend fun search(
        term: String,
        storefront: ItunesStorefront,
        limit: Int = DEFAULT_LIMIT,
        attribute: SearchAttribute = SearchAttribute.Any,
    ): List<PodcastSummary> {
        if (term.isBlank()) return emptyList()
        // Apple's iTunes Search API historically responds with `text/javascript` for
        // JSONP-era compatibility, which the default Ktor `json()` ContentNegotiation
        // matcher doesn't pick up — `.body<ItunesSearchResponse>()` then fails with
        // "no transformation found" even when the bytes are valid JSON. Decoding the
        // raw string with the shared lenient `kofipodJson` is content-type-agnostic
        // and protects against future header changes.
        val raw =
            client.get(SEARCH_ENDPOINT) {
                parameter("term", term)
                parameter("media", "podcast")
                parameter("entity", "podcast")
                parameter("country", storefront.iso2)
                parameter("limit", limit.coerceIn(1, MAX_LIMIT))
                when (attribute) {
                    SearchAttribute.Any -> Unit
                    SearchAttribute.TitleTerm -> parameter("attribute", "titleTerm")
                }
            }.bodyAsText()
        val response = kofipodJson.decodeFromString<ItunesSearchResponse>(raw)
        return response.results
            .filter { !it.feedUrl.isNullOrBlank() }
            .map { it.toSummary() }
    }

    enum class SearchAttribute { Any, TitleTerm }

    companion object {
        const val DEFAULT_LIMIT: Int = 30

        /** Apple caps `limit` at 200; we keep our own ceiling lower as a budget guard. */
        const val MAX_LIMIT: Int = 50

        private const val SEARCH_ENDPOINT = "https://itunes.apple.com/search"

        /** Apple's artwork URL ends in `100x100bb.jpg` / `600x600bb.jpg`; upscale on the client. */
        internal fun upscaleArtwork(url: String?): String {
            if (url.isNullOrBlank()) return ""
            return url.replace("100x100bb", "1200x1200bb")
                .replace("600x600bb", "1200x1200bb")
        }
    }
}

internal fun ItunesPodcastResult.toSummary(): PodcastSummary {
    val collection = collectionId.takeIf { it > 0L } ?: trackId
    val title = collectionName.ifBlank { trackName }
    return PodcastSummary(
        // Sentinel-prefixed id so a Long-parsing call site (e.g. PodcastDetailViewModel)
        // can detect this isn't a Podcast Index feedId and route to hydration before
        // navigating. Carries the iTunes collectionId for telemetry / future RSS lookup.
        id = "$ITUNES_ID_PREFIX$collection",
        // 0L sentinel = "no Podcast Index feedId yet". Set after tap-time hydration.
        feedId = 0L,
        title = title,
        author = artistName,
        description = "",
        artworkUrl = ItunesSearchApi.upscaleArtwork(artworkUrl600 ?: artworkUrl100),
        feedUrl = feedUrl.orEmpty(),
        category = primaryGenreName ?: genres.firstOrNull().orEmpty(),
        episodeCount = trackCount,
        categoryIds = emptyList(),
        sources = setOf(SourceId.ITunes),
    )
}

/**
 * Prefix marking a [PodcastSummary.id] that originated from an iTunes-only search
 * result. Downstream consumers (SearchViewModel tap handler) detect this prefix and
 * call `podcastByFeedUrl` against Podcast Index to convert the id into a numeric
 * feedId before navigating, since PodcastDetailViewModel assumes a numeric id.
 */
const val ITUNES_ID_PREFIX: String = "itunes:"

fun String.isItunesOnlyId(): Boolean = startsWith(ITUNES_ID_PREFIX)

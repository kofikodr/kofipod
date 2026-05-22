// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Apple iTunes Search API response envelope. The API returns a `resultCount` plus a
 * `results` array of heterogeneously-typed entries (podcasts, music, movies, …);
 * we constrain the query with `media=podcast&entity=podcast` so every entry here
 * is a podcast and we don't need a polymorphic decode.
 *
 * The shared [com.kofikodr.kofipod.data.net.kofipodJson] config is lenient
 * (`ignoreUnknownKeys = true`), so fields we don't care about — `kind`,
 * `releaseDate`, `genreIds`, etc. — are silently dropped without crashing the
 * decode if Apple adds new ones.
 */
@Serializable
internal data class ItunesSearchResponse(
    val resultCount: Int = 0,
    val results: List<ItunesPodcastResult> = emptyList(),
)

/**
 * One podcast entry from the iTunes Search API. Field names match the API verbatim
 * via [SerialName] so we can keep idiomatic Kotlin property names.
 *
 *  - `collectionId` / `trackId` are Apple's numeric IDs (usually the same value).
 *    We use `collectionId` as the stable iTunes-side identifier.
 *  - `feedUrl` is the RSS URL — the canonical key for cross-source dedup against
 *    Podcast Index results.
 *  - `artworkUrl600` is the largest artwork the API returns. The 600px URL has
 *    a documented pattern (`…/600x600bb.jpg`) we rewrite to `1200x1200bb.jpg` on
 *    the client for higher-density tablet displays — see [ItunesSearchApi].
 *  - `genres` is a name array; `primaryGenreName` is the canonical category for
 *    our `PodcastSummary.category` field.
 *  - Some results — especially short-lived feeds — omit `feedUrl`. Those are
 *    filtered out by [ItunesSearchApi] since a subscribe is meaningless without
 *    a feed URL.
 */
@Serializable
internal data class ItunesPodcastResult(
    val collectionId: Long = 0L,
    val trackId: Long = 0L,
    @SerialName("collectionName") val collectionName: String = "",
    @SerialName("trackName") val trackName: String = "",
    @SerialName("artistName") val artistName: String = "",
    val feedUrl: String? = null,
    @SerialName("artworkUrl600") val artworkUrl600: String? = null,
    @SerialName("artworkUrl100") val artworkUrl100: String? = null,
    val genres: List<String> = emptyList(),
    @SerialName("primaryGenreName") val primaryGenreName: String? = null,
    @SerialName("trackCount") val trackCount: Int = 0,
)

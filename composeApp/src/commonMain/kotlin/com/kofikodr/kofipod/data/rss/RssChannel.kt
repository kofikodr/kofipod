// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.rss

import kotlinx.datetime.Instant

/**
 * One podcast as represented by its publisher's RSS feed.
 *
 * The RSS feed is the publisher's own source of truth for show + episode metadata.
 * Both Podcast Index and Apple iTunes are aggregators that crawl this same XML;
 * fetching directly is how we defeat their crawl lag (see Slice B plan,
 * `docs/superpowers/plans/2026-05-23-itunes-rss-slice-b.md`).
 *
 * Only fields the app actually consumes are modeled — feeds carry many more optional
 * elements (atom:link, copyright, language, `<itunes:owner>`, `<itunes:type>` etc.)
 * that we deliberately ignore so the parser stays small. Add a field here when a UI
 * surface needs it, not speculatively.
 */
data class RssChannel(
    val title: String,
    val description: String,
    val link: String,
    val author: String,
    val imageUrl: String,
    val category: String,
    val episodes: List<RssEpisode>,
)

/**
 * One episode as parsed from an `<item>` in the channel.
 *
 * - [guid] is the publisher-assigned stable id. Fed by `<guid>` when present, falling
 *   back to the enclosure URL when the publisher omits it — some legacy feeds do.
 *   Used as the merge key against existing Podcast Index `Episode` rows; see
 *   `EpisodeMerger` (Slice B.3) for the precedence rules.
 * - [enclosure] carries the audio file pointer. Episodes without an enclosure are
 *   dropped at parse time — they're meaningless to a podcast app.
 * - [pubDate] is nullable rather than enforced: real-world feeds occasionally ship
 *   malformed `<pubDate>` values. We keep the episode and surface a null instead of
 *   throwing the whole feed away.
 */
data class RssEpisode(
    val guid: String,
    val title: String,
    val description: String,
    val link: String,
    val pubDate: Instant?,
    val enclosure: RssEnclosure,
    val durationSeconds: Long?,
    val episodeNumber: Int?,
    val seasonNumber: Int?,
    val explicit: Boolean,
    val imageUrl: String,
)

/**
 * The audio file the episode points at.
 *
 * Some feeds wrap their enclosure URL in an ad-stitching prefix (op3.dev, chartable.com,
 * pdst.fm) that 301-redirects to the real CDN. We keep whatever URL the publisher put
 * in the feed — Ktor follows redirects when the audio is actually fetched, and PI's
 * cached URL may use the same prefix. Canonicalization happens in `EpisodeMerger`'s
 * match-key logic, not here, so the raw URL remains available for audit.
 *
 * [lengthBytes] is the `length` attribute from `<enclosure>`. RFC says required, but
 * many feeds omit or lie about it — we store what we got and don't act on it.
 */
data class RssEnclosure(
    val url: String,
    val mimeType: String,
    val lengthBytes: Long?,
)

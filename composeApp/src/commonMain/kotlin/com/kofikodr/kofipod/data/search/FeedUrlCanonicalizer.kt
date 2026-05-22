// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.search

/**
 * Normalises a feed URL into a stable dedup key. Two URLs that point at the same RSS
 * feed but differ in scheme, casing, tracking params, or trailing slash collapse to
 * the same canonical form.
 *
 * Used by [SearchResultMerger] to merge the same podcast returned by multiple search
 * sources (e.g. Podcast Index + iTunes) into a single result with a combined source
 * set. The canonical string is NOT a valid replacement URL for fetching — it's only
 * a key — but in practice it's still a working https URL.
 *
 * Rules (in order):
 *  - Trim surrounding whitespace.
 *  - If the input doesn't begin with `http://` or `https://`, assume it's hostless
 *    and return as-is (the canonicaliser doesn't invent a scheme).
 *  - Force scheme to `https`. Podcast feeds are universally served over both; using
 *    `https` as the canonical form matches what indexes return today.
 *  - Lowercase the scheme + authority (host + optional port). Path segments stay
 *    case-sensitive because RSS hosts often expose case-significant paths.
 *  - Strip default ports (`:80`, `:443`).
 *  - Strip the URL fragment (`#…`).
 *  - Strip known tracking query params (`utm_*`, `fbclid`, `gclid`, `mc_eid`,
 *    `mc_cid`, `igshid`). Other query params are preserved — many real feeds use
 *    `?format=rss` or similar functional parameters.
 *  - Collapse trailing `/` on the path (`https://x.com/feed/` → `https://x.com/feed`).
 *    Empty paths stay empty (`https://x.com` → `https://x.com`).
 *
 * Out of scope (intentionally):
 *  - `www.` host stripping. Hosts that redirect `example.com` ↔ `www.example.com`
 *    will produce two canonical forms; the merger's title+author fallback catches
 *    the obvious collisions without us guessing.
 *  - Percent-encoding normalisation. Indexes already return decoded URLs.
 */
object FeedUrlCanonicalizer {
    private val TRACKING_PARAM_PREFIXES = listOf("utm_")
    private val TRACKING_PARAM_NAMES =
        setOf(
            "fbclid",
            "gclid",
            "mc_eid",
            "mc_cid",
            "igshid",
            "ref_src",
            "ref_url",
        )

    fun canonicalize(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return trimmed

        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return trimmed // hostless — return as-is, not our problem.

        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        if (scheme != "http" && scheme != "https") return trimmed

        val afterScheme = trimmed.substring(schemeEnd + 3)

        // Strip fragment FIRST so it doesn't pollute the query.
        val withoutFragment = afterScheme.substringBefore('#')

        val queryStart = withoutFragment.indexOf('?')
        val authorityAndPath =
            if (queryStart < 0) withoutFragment else withoutFragment.substring(0, queryStart)
        val rawQuery = if (queryStart < 0) null else withoutFragment.substring(queryStart + 1)

        val pathStart = authorityAndPath.indexOf('/')
        val authority = if (pathStart < 0) authorityAndPath else authorityAndPath.substring(0, pathStart)
        val path = if (pathStart < 0) "" else authorityAndPath.substring(pathStart)

        val normalisedAuthority = stripDefaultPort(authority.lowercase())
        val normalisedPath = path.trimEnd('/')

        val cleanedQuery = rawQuery?.let { cleanQuery(it) }
        val querySuffix = if (cleanedQuery.isNullOrEmpty()) "" else "?$cleanedQuery"

        return "https://$normalisedAuthority$normalisedPath$querySuffix"
    }

    private fun stripDefaultPort(authority: String): String =
        when {
            authority.endsWith(":80") -> authority.removeSuffix(":80")
            authority.endsWith(":443") -> authority.removeSuffix(":443")
            else -> authority
        }

    private fun cleanQuery(query: String): String =
        query.split('&')
            .filter { pair ->
                if (pair.isEmpty()) return@filter false
                val name = pair.substringBefore('=').lowercase()
                if (name in TRACKING_PARAM_NAMES) return@filter false
                if (TRACKING_PARAM_PREFIXES.any { name.startsWith(it) }) return@filter false
                true
            }
            .joinToString("&")
}

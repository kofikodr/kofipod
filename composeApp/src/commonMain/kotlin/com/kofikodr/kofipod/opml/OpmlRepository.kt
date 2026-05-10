// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.opml

import com.kofikodr.kofipod.data.repo.LibraryRepository
import com.kofikodr.kofipod.domain.PodcastSummary
import com.kofikodr.kofipod.util.slugifyName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Narrow seam over the Podcast Index `byFeedUrl` lookup, so [OpmlRepository] can be unit
 * tested without standing up the SDK client. Production binding is `api::resolveByUrl`.
 */
fun interface PodcastFeedLookup {
    /** Throws on lookup failure (HTTP error, no match, malformed response). */
    suspend fun resolve(feedUrl: String): PodcastSummary
}

/**
 * Glue between the OPML wire format and Kofipod's library state. Stays thin on purpose:
 * parsing is in [OpmlParser], folder/feed reads are in [LibraryRepository], and the
 * Podcast Index lookup is the SDK's `byFeedUrl`. This class owns the merge rules
 * (dedup-by-feedUrl, reuse-folder-by-name, lazy episode fetch).
 */
class OpmlRepository(
    private val library: LibraryRepository,
    private val lookup: PodcastFeedLookup,
    private val clock: Clock = Clock.System,
) {
    /**
     * Parse [bytes], walk the outline tree, and merge into the library:
     * - Folders create or reuse `PodcastList` rows by case-insensitive name. Nested OPML
     *   folders are flattened to a single level (Kofipod's `PodcastList` is flat); the
     *   **innermost** folder name wins so the most specific label is preserved.
     * - Feeds are looked up via Podcast Index `byFeedUrl`. If lookup fails, the entry is
     *   counted as `failed` (we never insert a podcast with a synthesized id, since the
     *   rest of the app assumes [com.kofikodr.kofipod.db.Podcast.id] is the Podcast Index feed id).
     * - Feeds whose `feedUrl` already exist in the library are counted as `skipped` and
     *   left in their current folder unchanged.
     *
     * Episode fetching is intentionally lazy — episodes arrive on detail-screen open or
     * via the daily worker. A 200-feed import would otherwise fan out to 200 API calls.
     */
    suspend fun import(bytes: ByteArray): ImportResult {
        val doc = parseOpml(bytes)
        val existing = library.podcastsNow()
        val existingByUrl = existing.associateBy { normalizeUrl(it.feedUrl) }
        val existingLists = library.listsNow()
        val listsByName: MutableMap<String, String> =
            existingLists.associate { it.name.lowercase() to it.id }.toMutableMap()
        var nextPosition = existingLists.size

        var imported = 0
        var skipped = 0
        var failed = 0
        val failedTitles = mutableListOf<String>()

        suspend fun handleFeed(
            feed: OpmlOutline.Feed,
            listId: String?,
        ) {
            if (existingByUrl.containsKey(normalizeUrl(feed.xmlUrl))) {
                skipped++
                return
            }
            val summary =
                runCatching { lookup.resolve(feed.xmlUrl) }
                    .getOrElse {
                        failed++
                        failedTitles += feed.title
                        return
                    }
            library.savePodcast(
                summary = summary,
                listId = listId,
                now = clock.now().toEpochMilliseconds(),
            )
            imported++
        }

        suspend fun walk(
            outlines: List<OpmlOutline>,
            currentListId: String?,
        ) {
            for (node in outlines) {
                when (node) {
                    is OpmlOutline.Feed -> handleFeed(node, currentListId)
                    is OpmlOutline.Folder -> {
                        // Innermost folder name wins: each folder always resolves to its
                        // own listId, replacing any outer parent's listId. This loses
                        // hierarchy depth (Kofipod's lists are flat) but keeps the most
                        // specific label rather than silently dropping the inner name.
                        val listId =
                            resolveOrCreateList(
                                name = node.name,
                                listsByName = listsByName,
                                nextPositionSupplier = { nextPosition++ },
                            )
                        walk(node.children, listId)
                    }
                }
            }
        }

        walk(doc.outlines, currentListId = null)

        return ImportResult(
            imported = imported,
            skipped = skipped,
            failed = failed,
            failedTitles = failedTitles.toList(),
        )
    }

    /**
     * Build the OPML payload for the user's full library. Folder order matches the
     * library UI (`PodcastList.position`); within each folder, podcasts are sorted by
     * title. Unfiled podcasts emit at the body root. Suspending and switching to
     * [Dispatchers.Default] (matching `LibraryRepository`'s flow dispatchers — IO isn't
     * available on iOS) so the SQLite reads don't block whatever dispatcher called us.
     */
    suspend fun export(): String =
        withContext(Dispatchers.Default) {
            val lists = library.listsNow().sortedBy { it.position }
            val byList = library.podcastsNow().groupBy { it.listId }
            val folders =
                lists.map { list ->
                    ExportFolder(
                        name = list.name,
                        feeds =
                            byList[list.id]
                                .orEmpty()
                                .sortedBy { it.title.lowercase() }
                                .map { ExportFeed(title = it.title, xmlUrl = it.feedUrl) },
                    )
                }
            val unfiled =
                byList[null]
                    .orEmpty()
                    .sortedBy { it.title.lowercase() }
                    .map { ExportFeed(title = it.title, xmlUrl = it.feedUrl) }
            OpmlSerializer.serialize(
                title = OPML_DOCUMENT_TITLE,
                folders = folders,
                unfiled = unfiled,
                generatedAtIso = clock.now().toString(),
            )
        }

    fun suggestedExportFilename(now: Instant = clock.now()): String {
        val iso = now.toString().substringBefore('T')
        return "kofipod-subscriptions-$iso.opml"
    }

    private fun resolveOrCreateList(
        name: String,
        listsByName: MutableMap<String, String>,
        nextPositionSupplier: () -> Int,
    ): String {
        val key = name.lowercase()
        listsByName[key]?.let { return it }
        val slug = slugifyName(name, listsByName.values.toSet())
        library.createList(
            id = slug,
            name = name,
            position = nextPositionSupplier(),
            now = clock.now().toEpochMilliseconds(),
        )
        listsByName[key] = slug
        return slug
    }

    /**
     * Per RFC 3986, only the scheme and host are case-insensitive — path, query, and
     * fragment must be preserved as-is. A naive `url.lowercase()` collapses
     * `https://x.com/Feed.rss` and `https://x.com/feed.rss` to the same key, which on a
     * case-sensitive server are distinct resources. So we only lowercase up to the host.
     */
    private fun normalizeUrl(url: String): String {
        val trimmed = url.trim()
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) return trimmed.lowercase()
        val pathStart = trimmed.indexOf('/', schemeEnd + 3).takeIf { it >= 0 } ?: trimmed.length
        return trimmed.substring(0, pathStart).lowercase() + trimmed.substring(pathStart)
    }

    private companion object {
        const val OPML_DOCUMENT_TITLE = "Kofipod subscriptions"
    }
}

data class ImportResult(
    val imported: Int,
    val skipped: Int,
    val failed: Int,
    val failedTitles: List<String>,
) {
    val totalSeen: Int get() = imported + skipped + failed
}

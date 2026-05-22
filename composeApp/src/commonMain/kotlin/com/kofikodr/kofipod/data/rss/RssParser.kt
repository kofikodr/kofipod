// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.data.rss

import kotlinx.datetime.Instant
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming

/**
 * Pure RSS-feed parser. Streams the XML once with `xmlutil`'s `XmlReader` so memory
 * stays bounded even for feeds with thousands of `<item>` entries (some publishers
 * ship 10–20 MB feeds with full history).
 *
 * Scope: just enough of RSS 2.0 + the iTunes podcast namespace to drive the app. We
 * deliberately don't aim for full RSS spec compliance — fields we don't render or
 * persist (atom:link, copyright, language, itunes:owner, itunes:type, etc.) are
 * skipped. Extend here when a UI surface needs more.
 *
 * Robustness rules:
 *  - Episodes without an `<enclosure>` are dropped — there's no audio to play.
 *  - Episodes with an unparseable `<pubDate>` are kept with `pubDate = null` rather
 *    than throwing the feed away.
 *  - Episodes without `<guid>` fall back to the enclosure URL as the GUID.
 *  - Unknown elements at any depth are skipped, not failed on. Real publisher feeds
 *    routinely carry custom namespaces (`media:`, `podcast:`, etc.) we don't model.
 */
object RssParser {
    private const val NS_ITUNES: String = "http://www.itunes.com/dtds/podcast-1.0.dtd"

    fun parse(body: String): RssChannel {
        // Defensive DTD reject. RSS 2.0 feeds in the wild do not legitimately use
        // DOCTYPE / internal subsets / external entity declarations — any feed
        // shipping one is either misformatted or actively hostile (XXE, billion-
        // laughs entity expansion). We don't know what the underlying xmlutil
        // backend does on each platform with `<!ENTITY ... SYSTEM "...">` or
        // self-referential entity definitions, so refuse to feed such input to
        // the parser at all.
        if (containsDoctypeDeclaration(body)) {
            return emptyChannel()
        }
        val reader = xmlStreaming.newReader(body)
        while (reader.hasNext()) {
            val event = reader.next()
            if (event == EventType.START_ELEMENT &&
                reader.localName == "channel" &&
                reader.namespaceURI.isNullOrEmpty()
            ) {
                return readChannel(reader)
            }
        }
        return emptyChannel()
    }

    private fun readChannel(reader: XmlReader): RssChannel {
        var title = ""
        var description = ""
        var link = ""
        var author = ""
        var imageUrl = ""
        var category = ""
        val episodes = mutableListOf<RssEpisode>()

        while (reader.hasNext()) {
            when (reader.next()) {
                EventType.START_ELEMENT -> {
                    val name = reader.localName
                    val ns = reader.namespaceURI.orEmpty()
                    when {
                        ns.isEmpty() && name == "title" -> title = readText(reader)
                        ns.isEmpty() && name == "description" -> description = readText(reader)
                        ns.isEmpty() && name == "link" -> link = readText(reader)
                        ns == NS_ITUNES && name == "author" -> author = readText(reader)
                        ns == NS_ITUNES && name == "image" -> {
                            // iTunes image is an empty element with href attribute. Prefer
                            // it over the legacy <image><url/></image> form when present.
                            val href = reader.getAttributeValue(null, "href").orEmpty()
                            if (href.isNotEmpty()) imageUrl = href
                            skipElement(reader)
                        }
                        ns == NS_ITUNES && name == "category" -> {
                            if (category.isEmpty()) {
                                category = reader.getAttributeValue(null, "text").orEmpty()
                            }
                            skipElement(reader)
                        }
                        ns.isEmpty() && name == "image" -> {
                            val nested = readLegacyImageUrl(reader)
                            if (imageUrl.isEmpty()) imageUrl = nested
                        }
                        ns.isEmpty() && name == "item" -> {
                            readItem(reader)?.let { episodes.add(it) }
                        }
                        else -> skipElement(reader)
                    }
                }
                EventType.END_ELEMENT -> {
                    if (reader.localName == "channel") {
                        return RssChannel(
                            title = title,
                            description = description,
                            link = link,
                            author = author,
                            imageUrl = imageUrl,
                            category = category,
                            episodes = episodes,
                        )
                    }
                }
                else -> Unit
            }
        }
        return RssChannel(
            title = title,
            description = description,
            link = link,
            author = author,
            imageUrl = imageUrl,
            category = category,
            episodes = episodes,
        )
    }

    private fun readItem(reader: XmlReader): RssEpisode? {
        var guid = ""
        var title = ""
        var description = ""
        var link = ""
        var pubDateRaw = ""
        var enclosureUrl = ""
        var enclosureType = ""
        var enclosureLength: Long? = null
        var durationRaw = ""
        var episodeNumber: Int? = null
        var seasonNumber: Int? = null
        var explicit = false
        var imageUrl = ""

        while (reader.hasNext()) {
            when (reader.next()) {
                EventType.START_ELEMENT -> {
                    val name = reader.localName
                    val ns = reader.namespaceURI.orEmpty()
                    when {
                        ns.isEmpty() && name == "guid" -> guid = readText(reader)
                        ns.isEmpty() && name == "title" -> title = readText(reader)
                        ns.isEmpty() && name == "description" -> description = readText(reader)
                        ns.isEmpty() && name == "link" -> link = readText(reader)
                        ns.isEmpty() && name == "pubDate" -> pubDateRaw = readText(reader)
                        ns.isEmpty() && name == "enclosure" -> {
                            enclosureUrl = reader.getAttributeValue(null, "url").orEmpty()
                            enclosureType = reader.getAttributeValue(null, "type").orEmpty()
                            enclosureLength = reader.getAttributeValue(null, "length")?.toLongOrNull()
                            skipElement(reader)
                        }
                        ns == NS_ITUNES && name == "duration" -> durationRaw = readText(reader)
                        ns == NS_ITUNES && name == "episode" -> episodeNumber = readText(reader).toIntOrNull()
                        ns == NS_ITUNES && name == "season" -> seasonNumber = readText(reader).toIntOrNull()
                        ns == NS_ITUNES && name == "explicit" -> explicit = parseExplicit(readText(reader))
                        ns == NS_ITUNES && name == "image" -> {
                            imageUrl = reader.getAttributeValue(null, "href").orEmpty()
                            skipElement(reader)
                        }
                        else -> skipElement(reader)
                    }
                }
                EventType.END_ELEMENT -> {
                    if (reader.localName == "item") {
                        return buildEpisode(
                            guid = guid,
                            title = title,
                            description = description,
                            link = link,
                            pubDateRaw = pubDateRaw,
                            enclosureUrl = enclosureUrl,
                            enclosureType = enclosureType,
                            enclosureLength = enclosureLength,
                            durationRaw = durationRaw,
                            episodeNumber = episodeNumber,
                            seasonNumber = seasonNumber,
                            explicit = explicit,
                            imageUrl = imageUrl,
                        )
                    }
                }
                else -> Unit
            }
        }
        return null
    }

    private fun buildEpisode(
        guid: String,
        title: String,
        description: String,
        link: String,
        pubDateRaw: String,
        enclosureUrl: String,
        enclosureType: String,
        enclosureLength: Long?,
        durationRaw: String,
        episodeNumber: Int?,
        seasonNumber: Int?,
        explicit: Boolean,
        imageUrl: String,
    ): RssEpisode? {
        if (enclosureUrl.isEmpty()) return null
        return RssEpisode(
            // GUID falls back to enclosure URL when the publisher omitted <guid>. Some
            // legacy feeds do; keeping the URL as id means the merge step still has a
            // stable key (it's the same URL across re-crawls until the publisher rotates).
            guid = guid.ifEmpty { enclosureUrl },
            title = title,
            description = description,
            link = link,
            pubDate = parseRfc2822(pubDateRaw),
            enclosure =
                RssEnclosure(
                    url = enclosureUrl,
                    mimeType = enclosureType,
                    lengthBytes = enclosureLength,
                ),
            durationSeconds = parseDuration(durationRaw),
            episodeNumber = episodeNumber,
            seasonNumber = seasonNumber,
            explicit = explicit,
            imageUrl = imageUrl,
        )
    }

    private fun readLegacyImageUrl(reader: XmlReader): String {
        var url = ""
        while (reader.hasNext()) {
            when (reader.next()) {
                EventType.START_ELEMENT -> {
                    if (reader.localName == "url" && reader.namespaceURI.isNullOrEmpty()) {
                        url = readText(reader)
                    } else {
                        skipElement(reader)
                    }
                }
                EventType.END_ELEMENT -> {
                    if (reader.localName == "image") return url
                }
                else -> Unit
            }
        }
        return url
    }

    private fun readText(reader: XmlReader): String {
        val sb = StringBuilder()
        while (reader.hasNext()) {
            when (val ev = reader.next()) {
                EventType.TEXT, EventType.CDSECT -> sb.append(reader.text)
                EventType.START_ELEMENT -> skipElement(reader)
                EventType.END_ELEMENT -> return sb.toString().trim()
                else -> {
                    if (ev == EventType.END_DOCUMENT) return sb.toString().trim()
                }
            }
        }
        return sb.toString().trim()
    }

    private fun skipElement(reader: XmlReader) {
        var depth = 1
        while (depth > 0 && reader.hasNext()) {
            when (reader.next()) {
                EventType.START_ELEMENT -> depth++
                EventType.END_ELEMENT -> depth--
                else -> Unit
            }
        }
    }

    /**
     * Best-effort scan of the prolog for a `<!DOCTYPE` declaration. XML declarations
     * and the root element can precede it, so we scan up to a generous fixed prefix
     * rather than the whole body — DOCTYPE must legally appear before the root
     * element, so anything past the first few KB is by definition not a DOCTYPE.
     *
     * Case-insensitive because XML keywords are case-sensitive but real-world feeds
     * misformat surprisingly often, and accepting `<!doctype` etc here is safer than
     * letting it through.
     */
    internal fun containsDoctypeDeclaration(body: String): Boolean {
        val scanLimit = minOf(body.length, DOCTYPE_SCAN_PREFIX)
        val prefix = body.substring(0, scanLimit)
        // Match `<!DOCTYPE` with optional whitespace between `<!` and `DOCTYPE`. Real
        // feeds occasionally embed exclamation-marked comments (`<!--`), so we
        // require an upper-or-lowercase D following the `<!` to disambiguate.
        return Regex("""<!\s*DOCTYPE\b""", RegexOption.IGNORE_CASE).containsMatchIn(prefix)
    }

    private const val DOCTYPE_SCAN_PREFIX = 4096

    private fun emptyChannel(): RssChannel =
        RssChannel(
            title = "",
            description = "",
            link = "",
            author = "",
            imageUrl = "",
            category = "",
            episodes = emptyList(),
        )

    /**
     * Parses iTunes `<itunes:duration>` values. Three on-the-wire shapes seen in real
     * feeds: bare seconds ("1820"), MM:SS ("30:20"), HH:MM:SS ("01:30:20"). Returns
     * null on anything that doesn't match — caller treats null as "duration unknown".
     */
    internal fun parseDuration(raw: String): Long? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        trimmed.toLongOrNull()?.let { return it }
        val parts = trimmed.split(":").mapNotNull { it.toLongOrNull() }
        return when (parts.size) {
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            2 -> parts[0] * 60 + parts[1]
            else -> null
        }
    }

    /**
     * `<itunes:explicit>` historically accepted "yes"/"no"; Apple has since blessed
     * "true"/"false" too. Anything else (or absence) → false.
     */
    internal fun parseExplicit(raw: String): Boolean = raw.trim().lowercase() in EXPLICIT_TRUE_VALUES

    private val EXPLICIT_TRUE_VALUES = setOf("yes", "true", "1", "explicit")

    private val MONTH_LOOKUP =
        mapOf(
            "Jan" to 1, "Feb" to 2, "Mar" to 3, "Apr" to 4,
            "May" to 5, "Jun" to 6, "Jul" to 7, "Aug" to 8,
            "Sep" to 9, "Oct" to 10, "Nov" to 11, "Dec" to 12,
        )

    /**
     * Parses RFC 2822 dates as used in `<pubDate>`. The format is
     * `[Day, ]DD Mon YYYY HH:MM[:SS] (offset|zone)` — kotlinx-datetime doesn't ship a
     * stdlib parser for this in commonMain, so this is a tight hand-rolled one. We aim
     * for the 99% shape; anything weird returns null and the episode keeps a null
     * pubDate rather than being dropped.
     */
    internal fun parseRfc2822(raw: String): Instant? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val tokens = trimmed.split(Regex("\\s+"))
        // Optional day-of-week prefix ending in ','. Strip it.
        val start = if (tokens.isNotEmpty() && tokens[0].endsWith(",")) 1 else 0
        if (tokens.size - start < 4) return null
        val day = tokens[start].toIntOrNull() ?: return null
        val month = MONTH_LOOKUP[tokens[start + 1].take(3)] ?: return null
        val year = tokens[start + 2].toIntOrNull() ?: return null
        val timeParts = tokens[start + 3].split(":")
        if (timeParts.size < 2) return null
        val hour = timeParts[0].toIntOrNull() ?: return null
        val minute = timeParts[1].toIntOrNull() ?: return null
        val second = if (timeParts.size >= 3) timeParts[2].toIntOrNull() ?: 0 else 0
        val tz = if (tokens.size > start + 4) tokens[start + 4] else "+0000"
        val offsetSeconds = parseTimezoneOffsetSeconds(tz) ?: return null
        val sign = if (offsetSeconds >= 0) "+" else "-"
        val absH = (kotlinAbs(offsetSeconds) / 3600).pad(2)
        val absM = ((kotlinAbs(offsetSeconds) % 3600) / 60).pad(2)
        val iso = "${year.pad(4)}-${month.pad(2)}-${day.pad(2)}T${hour.pad(2)}:${minute.pad(2)}:${second.pad(2)}$sign$absH:$absM"
        return try {
            Instant.parse(iso)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun parseTimezoneOffsetSeconds(tz: String): Int? {
        if (tz.isEmpty()) return 0
        if (tz[0] == '+' || tz[0] == '-') {
            if (tz.length != 5) return null
            val sign = if (tz[0] == '+') 1 else -1
            val h = tz.substring(1, 3).toIntOrNull() ?: return null
            val m = tz.substring(3, 5).toIntOrNull() ?: return null
            return sign * (h * 3600 + m * 60)
        }
        return when (tz.uppercase()) {
            "GMT", "UTC", "Z", "UT" -> 0
            "EST" -> -5 * 3600
            "EDT" -> -4 * 3600
            "CST" -> -6 * 3600
            "CDT" -> -5 * 3600
            "MST" -> -7 * 3600
            "MDT" -> -6 * 3600
            "PST" -> -8 * 3600
            "PDT" -> -7 * 3600
            else -> null
        }
    }

    private fun Int.pad(width: Int): String = toString().padStart(width, '0')

    private fun kotlinAbs(v: Int): Int = if (v < 0) -v else v
}

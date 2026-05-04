// SPDX-License-Identifier: GPL-3.0-or-later
package app.kofipod.opml

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import org.xmlpull.v1.XmlPullParserFactory
import java.io.ByteArrayInputStream

actual fun parseOpml(bytes: ByteArray): OpmlDocument {
    val parser =
        try {
            XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }.newPullParser()
        } catch (e: XmlPullParserException) {
            throw OpmlParseException("Couldn't initialise XML parser", e)
        }
    try {
        parser.setInput(ByteArrayInputStream(bytes), null)
        return readOpml(parser)
    } catch (e: XmlPullParserException) {
        throw OpmlParseException("Malformed OPML: ${e.message}", e)
    } catch (e: java.io.IOException) {
        throw OpmlParseException("Couldn't read OPML: ${e.message}", e)
    }
}

private fun readOpml(parser: XmlPullParser): OpmlDocument {
    var documentTitle: String? = null
    var outlines: List<OpmlOutline> = emptyList()
    var event = parser.eventType
    var sawOpmlRoot = false
    while (event != XmlPullParser.END_DOCUMENT) {
        if (event == XmlPullParser.START_TAG) {
            when (parser.name?.lowercase()) {
                "opml" -> sawOpmlRoot = true
                "title" -> if (documentTitle == null) documentTitle = readText(parser)
                "body" -> outlines = readOutlines(parser)
            }
        }
        event = parser.next()
    }
    if (!sawOpmlRoot) throw OpmlParseException("Missing <opml> root element")
    return OpmlDocument(title = documentTitle, outlines = outlines)
}

private fun readOutlines(parser: XmlPullParser): List<OpmlOutline> {
    val out = mutableListOf<OpmlOutline>()
    val depth = parser.depth
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.START_TAG -> {
                if (parser.name.equals("outline", ignoreCase = true)) {
                    parseOutline(parser)?.let { out += it }
                }
            }
            XmlPullParser.END_TAG -> if (parser.depth <= depth) return out
        }
    }
    return out
}

private fun parseOutline(parser: XmlPullParser): OpmlOutline? {
    val xmlUrl = parser.attr("xmlUrl")?.takeIf { it.isNotBlank() }
    val text = parser.attr("text")?.takeIf { it.isNotBlank() }
    val title = parser.attr("title")?.takeIf { it.isNotBlank() }
    val type = parser.attr("type")?.lowercase()
    val displayName = text ?: title ?: ""
    if (xmlUrl != null) {
        // RSS feed leaf — accept even when type is missing or non-rss; many exports omit type.
        skipChildren(parser)
        if (displayName.isBlank()) return OpmlOutline.Feed(title = xmlUrl, xmlUrl = xmlUrl)
        return OpmlOutline.Feed(title = displayName, xmlUrl = xmlUrl)
    }
    // Container outline. Walk children. Drop entries that aren't either a folder or feed
    // (e.g. links, includes) by virtue of `parseOutline` returning null for them.
    if (type != null && type != "folder" && type != "rss" && type != "podcast") {
        skipChildren(parser)
        return null
    }
    val children = readOutlines(parser)
    // Drop empty containers: a `<outline>` without xmlUrl AND without children is either
    // a non-rss bookmark/link or just empty noise. Either way, importing it as a folder
    // would create a useless empty PodcastList.
    if (children.isEmpty()) return null
    if (displayName.isBlank()) return null
    return OpmlOutline.Folder(name = displayName, children = children)
}

private fun XmlPullParser.attr(name: String): String? {
    for (i in 0 until attributeCount) {
        if (getAttributeName(i).equals(name, ignoreCase = true)) return getAttributeValue(i)
    }
    return null
}

private fun skipChildren(parser: XmlPullParser) {
    val depth = parser.depth
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        if (parser.eventType == XmlPullParser.END_TAG && parser.depth <= depth) return
    }
}

private fun readText(parser: XmlPullParser): String {
    val sb = StringBuilder()
    while (parser.next() != XmlPullParser.END_DOCUMENT) {
        when (parser.eventType) {
            XmlPullParser.TEXT -> sb.append(parser.text)
            XmlPullParser.END_TAG -> return sb.toString().trim()
            XmlPullParser.START_TAG -> skipChildren(parser)
        }
    }
    return sb.toString().trim()
}

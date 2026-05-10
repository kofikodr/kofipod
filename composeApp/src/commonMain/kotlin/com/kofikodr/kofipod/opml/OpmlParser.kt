// SPDX-License-Identifier: GPL-3.0-or-later
package com.kofikodr.kofipod.opml

class OpmlParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Parse an OPML 2.0 document. Tolerant: unknown attributes/elements are ignored.
 * Non-rss `<outline>` leaves (e.g. links) are dropped. Throws [OpmlParseException]
 * only on malformed XML or a missing `<opml>` root.
 *
 * iOS actual is a stub (see CLAUDE.md — iOS targets stay compiling but feature-parity is
 * not in scope). Android uses XmlPullParser.
 */
expect fun parseOpml(bytes: ByteArray): OpmlDocument
